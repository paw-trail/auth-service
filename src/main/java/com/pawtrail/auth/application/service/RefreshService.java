package com.pawtrail.auth.application.service;

import com.pawtrail.auth.domain.exception.AuthErrorCode;
import com.pawtrail.auth.domain.model.Account;
import com.pawtrail.auth.domain.model.RefreshTokenLog;
import com.pawtrail.auth.domain.repository.AccountRepository;
import com.pawtrail.auth.domain.repository.RefreshTokenLogRepository;
import com.pawtrail.auth.domain.repository.RefreshTokenStore;
import com.pawtrail.auth.infrastructure.config.AuthProperties;
import com.pawtrail.auth.infrastructure.security.TokenProvider;
import com.pawtrail.auth.infrastructure.security.TokenReader;
import com.pawtrail.common.exception.CustomException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 액세스 토큰을 다시 발급합니다.
 *
 * 액세스 토큰 수명이 30분이라 그 뒤로는 이 경로가 없으면 다시 로그인해야 합니다.
 * 프론트는 401 을 받으면 이것을 부르고 원래 요청을 재시도합니다.
 *
 * 갱신할 때 리프레시 토큰도 함께 새로 발급합니다
 *
 * 토큰이 복제됐을 때 수명을 짧게 끊기 위해서이고,
 * 이미 쓴 토큰이 다시 들어오는 것을 복제의 신호로 삼을 수 있기 때문입니다.
 *
 * 다만 그것만으로는 정상 사용자가 튕깁니다
 *
 * 탭 두 개가 같은 쿠키로 동시에 갱신을 부르면 뒤엣것은 이미 교체된 토큰을 들고 옵니다.
 * 응답을 못 받고 몇 초 뒤 재시도하는 경우도 서버가 보기에는 같은 모양입니다.
 * 이것을 곧바로 복제로 처리하면 모든 기기에서 로그아웃되고,
 * 증상이 "가끔 로그아웃된다" 로만 나타나 원인을 짚기 어렵습니다.
 *
 * 그래서 교체된 토큰을 짧은 시간 남겨 두고 그 안의 재사용은 경합으로 봅니다.
 * 그때는 앞서 발급한 토큰을 그대로 다시 내줍니다. 두 요청이 같은 결과를 받아야 하기 때문입니다.
 * 시간이 지난 뒤의 재사용만 복제로 보고 그 계정의 토큰을 전부 폐기합니다.
 *
 * 순서가 이 기능의 핵심입니다
 *
 * 자리를 먼저 잡고 그다음에 토큰을 씁니다.
 * 반대로 하면 토큰을 쓴 순간부터 자리가 놓일 때까지 아무 흔적도 없는 구간이 생기고,
 * 동시에 들어온 요청이 그 구간에 닿으면 아무 잘못 없이 복제로 판정됩니다.
 * 그 구간을 최대한 줄여 봤지만 병렬 요청 두 개에 계속 걸렸습니다.
 * 자리를 먼저 잡으면 판정이 한 번에 갈려 그 구간 자체가 없어집니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshService {

    private final AccountRepository accountRepository;
    private final RefreshTokenLogRepository refreshTokenLogRepository;
    private final RefreshTokenStore refreshTokenStore;
    private final TokenRevokeService tokenRevokeService;
    private final TokenReader tokenReader;
    private final TokenProvider tokenProvider;
    private final AuthProperties authProperties;

    /**
     * 갱신 결과입니다.
     *
     * 컨트롤러가 쿠키로 바꿔 심어야 하므로 값과 만료 시각을 함께 돌려줍니다.
     * 쿠키를 만드는 일을 이 계층에서 하지 않는 것은 그것이 HTTP 의 사정이기 때문입니다.
     *
     * 리프레시 토큰을 IssuedToken 이 아니라 문자열로 돌려주는 이유는
     * 경합으로 판정된 경우 방금 만든 것이 아니라 앞서 발급해 둔 것을 그대로 내보내기 때문입니다.
     */
    public record RefreshResult(String accessToken, Instant accessExpiresAt,
                                String refreshToken, Instant refreshExpiresAt) {
    }

    /**
     * 리프레시 토큰으로 새 토큰을 발급합니다.
     *
     * @param refreshTokenValue 쿠키에서 꺼낸 토큰 문자열입니다.
     * @param ipAddress null 을 허용합니다. 무엇을 넣을지는 아직 정해지지 않았습니다.
     * @param userAgent 브라우저가 보낸 값입니다.
     * @throws CustomException 읽을 수 없거나, 이미 무효이거나, 복제로 판정된 토큰입니다.
     */
    @Transactional
    public RefreshResult refresh(String refreshTokenValue, String ipAddress, String userAgent) {

        // 서명과 만료와 종류를 확인함
        // 액세스 토큰을 여기로 보내는 것도 이 단계에서 걸림
        TokenReader.RefreshTokenInfo info = tokenReader.readRefreshToken(refreshTokenValue);

        // 계정 확인을 저장소보다 먼저 함
        //
        // 순서가 반대이면 사고가 남
        // 아래에서 자리를 잡거나 토큰을 써 버린 뒤에 계정 검사로 걸려 예외를 던지면
        // 저장소만 바뀐 채로 요청이 끝나고, 같은 토큰으로 들어온 다음 요청의 판정이 뒤틀림
        Account account = accountRepository.findById(info.accountId())
                .orElseThrow(() -> {
                    // 우리가 발급한 토큰인데 그 계정이 없는 경우라 정상이 아님
                    log.warn("토큰의 계정을 찾지 못했습니다. accountId={}", info.accountId());
                    return new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
                });

        if (!account.canLogin()) {
            throw new CustomException(AuthErrorCode.ACCOUNT_WITHDRAWN);
        }

        // 폐기 기준 시각보다 앞서 발급된 토큰인지 봄
        //
        // 비밀번호를 바꾸면 그 시각이 올라가므로 이전에 발급된 토큰은 여기서 거부됨
        // 지우는 것이 없어도 무효가 되는 방식이며 계정으로 토큰을 찾을 수 없는 구조라 이렇게 처리함
        if (!account.isTokenValid(info.issuedAt())) {
            log.info("폐기된 토큰으로 갱신을 시도했습니다. accountId={}", account.getId());
            throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        // 토큰을 먼저 만듦
        //
        // 자리를 잡을 때 새 토큰을 함께 남겨야 하므로 그 전에 있어야 함
        // 자리를 못 잡으면 여기서 만든 것은 버려지는데, 저장소에 넣기 전이라 아무 데도 남지 않음
        TokenProvider.IssuedToken accessToken =
                tokenProvider.issueAccessToken(account.getId(), account.getRole());
        TokenProvider.IssuedToken refreshToken =
                tokenProvider.issueRefreshToken(account.getId(), account.getRole());

        // 교체할 자리를 잡음
        //
        // Redis 안에서 한 번에 갈리므로 동시에 들어온 요청 중 하나만 참을 받음
        // 진 요청은 이긴 쪽이 남긴 토큰을 그대로 받아 가며, 여기서는 401 이 나올 수 없음
        boolean acquired = refreshTokenStore.saveGraceIfAbsent(
                info.tokenId(), refreshToken.value(), authProperties.rotationGrace());

        if (!acquired) {
            return handleConcurrentRefresh(info, account, accessToken);
        }

        // 자리를 잡았어도 그 토큰이 살아 있다는 뜻은 아님
        // 유예가 지난 뒤의 재사용도 항목이 비어 있어 위에서 참을 받음
        Optional<UUID> claimed = refreshTokenStore.claim(info.tokenId());

        if (claimed.isEmpty()) {
            return handleReusedToken(info, account);
        }

        return rotate(info, account, accessToken, refreshToken, ipAddress, userAgent);
    }

    /**
     * 다른 요청이 이미 같은 토큰을 교체하고 있을 때의 처리입니다.
     *
     * 새로 발급하지 않고 그쪽이 남긴 토큰을 그대로 내보냅니다.
     * 새로 발급하면 두 요청이 서로 다른 토큰을 받고, 나중에 응답한 쪽이 쿠키를 덮어써서
     * 먼저 응답받은 쪽의 토큰이 주인을 잃습니다.
     */
    private RefreshResult handleConcurrentRefresh(TokenReader.RefreshTokenInfo info,
                                                  Account account,
                                                  TokenProvider.IssuedToken accessToken) {

        Optional<String> grace = refreshTokenStore.findGrace(info.tokenId());

        if (grace.isEmpty()) {
            // 자리는 잡혀 있었는데 값이 없는 경우임
            //
            // 자리를 잡지 못한 직후에 그 항목의 수명이 다한 아주 좁은 경계에서만 생김
            // 복제라고 단정할 수 없으므로 폐기하지 않고 거부만 함
            log.warn("교체 중인 토큰을 찾지 못했습니다. accountId={}", account.getId());
            throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        // 액세스 토큰은 위에서 미리 만들어 둔 것을 그대로 씀
        // 저장소에 기록되지 않는 값이라 여러 개가 살아 있어도 문제가 없고
        // 유예 항목에 담아 두면 값이 둘이 되어 다룰 것이 늘어남
        String replacement = grace.get();
        TokenReader.RefreshTokenInfo replacementInfo = tokenReader.readRefreshToken(replacement);

        log.info("동시 갱신으로 보고 앞서 발급한 토큰을 다시 내보냅니다. accountId={}",
                account.getId());

        return new RefreshResult(
                accessToken.value(), accessToken.expiresAt(),
                replacement, replacementInfo.expiresAt());
    }

    /**
     * 유예가 지난 뒤에 다시 들어온 토큰을 처리합니다.
     *
     * 이 토큰을 들고 있는 쪽이 둘이라는 뜻이고 어느 쪽이 진짜인지 알 수 없습니다.
     * 그래서 하나만 막지 않고 그 계정의 토큰을 전부 폐기해 다시 로그인하게 만듭니다.
     */
    private RefreshResult handleReusedToken(TokenReader.RefreshTokenInfo info, Account account) {

        // 방금 잡은 자리를 돌려줌
        //
        // 그대로 두면 뒤이은 재사용이 여기 담긴 토큰을 경합으로 받아 가는데
        // 그 토큰은 저장소에 기록된 적이 없어 아무 데도 쓸 수 없음
        // 쓸 수 없는 값을 성공으로 돌려주는 상태가 되어 원인을 짚기 어려워짐
        refreshTokenStore.deleteGrace(info.tokenId());

        // 폐기가 먼저 커밋되어야 하므로 새 트랜잭션으로 도는 쪽을 부름
        // 이 트랜잭션 안에서 하면 아래 예외에 딸려 함께 되돌아감
        //
        // 위에서 읽은 account 를 고치지 않은 상태여야 함
        // 고쳐 두면 안쪽이 올린 기준선을 바깥의 낡은 값이 덮음
        tokenRevokeService.revokeAllInNewTransaction(
                account.getId(), "리프레시 토큰 재사용 탐지");

        throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }

    /**
     * 토큰을 교체합니다.
     */
    private RefreshResult rotate(TokenReader.RefreshTokenInfo info, Account account,
                                 TokenProvider.IssuedToken accessToken,
                                 TokenProvider.IssuedToken refreshToken,
                                 String ipAddress, String userAgent) {

        // 저장소를 지금 바로 고침. 커밋 뒤로 미루지 않음
        //
        // 로그인은 미루지만 여기는 다름
        // 위에서 claim 이 옛 기록을 이미 지웠으므로 새 기록을 커밋 뒤로 미루면
        // 그 사이에 이 토큰으로 갱신을 시도하는 요청이 아무것도 찾지 못함
        //
        // 대신 커밋이 실패하면 저장소에는 새 토큰이 남고 이력 행만 없는 상태가 됨
        // 토큰은 정상 동작하고 조사용 기록 한 줄이 비는 것이라 앞의 위험보다 가벼움
        Duration refreshTtl = Duration.between(Instant.now(), refreshToken.expiresAt());
        refreshTokenStore.save(refreshToken.tokenId(), account.getId(), refreshTtl);

        LocalDateTime now = LocalDateTime.now();

        // 옛 이력 행에 회수 시각을 남기고 사슬 식별자를 물려받음
        //
        // 행을 찾지 못하는 것은 이력만 지워진 경우라 정상이 아님
        // 그때는 새 사슬로 시작함. 갱신 자체를 막을 이유는 없음
        Optional<RefreshTokenLog> oldLog = refreshTokenLogRepository.findByTokenId(info.tokenId());
        UUID loginId;

        if (oldLog.isPresent()) {
            oldLog.get().revoke(now);
            loginId = oldLog.get().getLoginId();
        } else {
            log.warn("교체할 이력 행을 찾지 못해 새 사슬로 시작합니다. accountId={}", account.getId());
            loginId = UUID.randomUUID();
        }

        refreshTokenLogRepository.save(RefreshTokenLog.issue(
                account.getId(),
                loginId,
                refreshToken.tokenId(),
                now,
                toLocalDateTime(refreshToken.expiresAt()),
                ipAddress,
                userAgent));

        log.info("토큰을 갱신했습니다. accountId={}", account.getId());

        return new RefreshResult(
                accessToken.value(), accessToken.expiresAt(),
                refreshToken.value(), refreshToken.expiresAt());
    }

    // 토큰은 Instant 로 시각을 다루고 엔티티는 LocalDateTime 을 씀
    //
    // 컨테이너 시간대를 서울로 고정해 두었으므로 시스템 기본 시간대로 변환하면 맞음
    // 그 설정이 빠지면 아홉 시간이 어긋나는데 timestamp 컬럼이라 데이터베이스가 바로잡지 않음
    private LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
