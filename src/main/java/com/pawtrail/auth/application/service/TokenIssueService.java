package com.pawtrail.auth.application.service;

import com.pawtrail.auth.application.dto.input.ClientInfo;
import com.pawtrail.auth.application.support.AfterCommitExecutor;
import com.pawtrail.auth.domain.model.Account;
import com.pawtrail.auth.domain.model.RefreshTokenLog;
import com.pawtrail.auth.domain.repository.RefreshTokenLogRepository;
import com.pawtrail.auth.domain.repository.RefreshTokenStore;
import com.pawtrail.auth.infrastructure.security.TokenProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 한 번을 시작합니다.
 *
 * 사람을 확인하는 방법은 여럿이지만 확인이 끝난 뒤에 하는 일은 하나입니다.
 * 토큰 두 개를 발급하고, 저장소와 이력에 남기고, 마지막 로그인 시각을 갱신합니다.
 * 그 일을 여기 모아 두면 이메일 로그인과 소셜 로그인이 같은 것을 씁니다.
 *
 * TokenRevokeService 와 짝입니다.
 * 그쪽이 토큰 수명을 끊는 일을 모아 두었고 이쪽은 여는 일을 모아 둡니다.
 *
 * 갱신은 여기를 쓰지 않습니다.
 * 로테이션은 로그인 식별자를 옛 이력에서 물려받고 저장소도 교체 명령을 쓰므로
 * 겉모습만 비슷할 뿐 다른 일입니다. 억지로 묶으면 인자로 갈라지는 함수가 됩니다.
 */
@Service
@RequiredArgsConstructor
public class TokenIssueService {

    private final RefreshTokenLogRepository refreshTokenLogRepository;
    private final RefreshTokenStore refreshTokenStore;
    private final TokenProvider tokenProvider;
    private final AfterCommitExecutor afterCommitExecutor;

    /**
     * 발급된 토큰 두 개입니다.
     *
     * 부르는 쪽이 이것을 쿠키로 바꿔 심습니다.
     * 쿠키를 여기서 만들지 않는 것은 그것이 HTTP 의 사정이기 때문입니다.
     */
    public record IssuedSession(TokenProvider.IssuedToken accessToken,
                                TokenProvider.IssuedToken refreshToken) {
    }

    /**
     * 토큰을 발급하고 저장소와 이력에 남깁니다.
     *
     * 트랜잭션을 요구합니다.
     * 이력을 저장하고 계정을 고치는 일이 부르는 쪽의 다른 쓰기와 함께 묶여야 하고,
     * 저장소 쓰기를 커밋 뒤로 미루려면 진행 중인 트랜잭션이 있어야 하기 때문입니다.
     * 없이 부르면 즉시 예외가 나므로 빠뜨려도 조용히 지나가지 않습니다.
     *
     * @param account 확인이 끝난 계정입니다. 로그인할 수 있는 상태인지는 부르는 쪽이 봅니다.
     * @param client  발급 이력에 남길 접속 정보입니다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedSession issue(Account account, ClientInfo client) {
        TokenProvider.IssuedToken accessToken =
                tokenProvider.issueAccessToken(account.getId(), account.getRole());
        TokenProvider.IssuedToken refreshToken =
                tokenProvider.issueRefreshToken(account.getId(), account.getRole());

        // 리프레시 토큰을 두 곳에 남김, 목적이 다름
        //
        //   저장소   아직 쓸 수 있는 토큰인가를 판단함, 로그아웃하면 지움
        //   이력     언제 어디서 발급됐는가를 남김, 지우지 않음
        //
        // 저장소 쪽만 커밋 뒤로 미룸
        // 이력은 데이터베이스라 트랜잭션에 함께 묶이지만 저장소는 롤백 대상이 아님
        // 먼저 써 두면 계정 저장이 실패했을 때 이력에 없는 토큰이 만료까지 살아 있게 됨
        //
        // 남은 수명을 람다 밖에서 계산하는 것이 중요함
        // 안에 두면 커밋 시점에 계산되어 그 사이 흐른 만큼 수명이 줄어듦
        Duration refreshTtl = Duration.between(Instant.now(), refreshToken.expiresAt());
        afterCommitExecutor.run(
                () -> refreshTokenStore.save(refreshToken.tokenId(), account.getId(), refreshTtl),
                "리프레시 토큰 저장");

        // 이 로그인 한 번을 묶는 값을 여기서 만듦
        //
        // 갱신할 때마다 리프레시 토큰이 교체되면서 이력에 행이 하나씩 쌓이는데
        // 그 행들은 이 값을 그대로 물려받아 한 사슬이 됨
        // 없으면 기기가 둘 이상일 때 어느 행이 어느 로그인에서 시작됐는지 알 수 없고,
        // 마지막 행의 발급 시각은 로그인 시각이 아니라 마지막 갱신 시각임
        //
        // 인증 판단에는 쓰이지 않음, 이력을 사람이 읽을 때만 쓰는 값임
        UUID loginId = UUID.randomUUID();

        refreshTokenLogRepository.save(RefreshTokenLog.issue(
                account.getId(),
                loginId,
                refreshToken.tokenId(),
                toLocalDateTime(Instant.now()),
                toLocalDateTime(refreshToken.expiresAt()),
                client.ipAddress(),
                client.userAgent()));

        account.updateLastLoginAt(LocalDateTime.now());

        return new IssuedSession(accessToken, refreshToken);
    }

    // 토큰은 Instant 로 시각을 다루고 엔티티는 LocalDateTime 을 씀
    //
    // 컨테이너 시간대를 서울로 고정해 두었으므로 시스템 기본 시간대로 변환하면 맞음
    // 그 설정이 빠지면 아홉 시간이 어긋나는데 timestamp 컬럼이라 데이터베이스가 바로잡지 않음
    private LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
