package com.pawtrail.auth.application.service;

import com.pawtrail.auth.application.dto.response.AccountResponse;
import com.pawtrail.auth.application.support.AfterCommitExecutor;
import com.pawtrail.auth.domain.event.payload.AccountCreatedEvent;
import com.pawtrail.auth.domain.exception.AuthErrorCode;
import com.pawtrail.auth.domain.model.Account;
import com.pawtrail.auth.domain.model.RefreshTokenLog;
import com.pawtrail.auth.domain.repository.AccountRepository;
import com.pawtrail.auth.domain.repository.RefreshTokenLogRepository;
import com.pawtrail.auth.domain.repository.EmailVerificationStore;
import com.pawtrail.auth.domain.repository.RefreshTokenStore;
import com.pawtrail.auth.infrastructure.security.TokenProvider;
import com.pawtrail.common.exception.CustomException;
import com.pawtrail.common.message.outbox.OutboxEventRecorder;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원가입과 로그인을 처리합니다.
 *
 * 이 계층은 순서를 정하는 곳입니다.
 * "조건이 맞는가" 같은 판단은 도메인에 맡기고, 여기서는
 * 확인하고, 저장하고, 이벤트를 기록하고, 응답을 조립하는 차례만 정합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AccountRepository accountRepository;
    private final RefreshTokenLogRepository refreshTokenLogRepository;
    private final RefreshTokenStore refreshTokenStore;
    private final PasswordEncoder passwordEncoder;
    private final TokenProvider tokenProvider;
    private final OutboxEventRecorder outboxEventRecorder;
    private final EmailVerificationStore emailVerificationStore;
    private final AfterCommitExecutor afterCommitExecutor;

    /**
     * 회원가입입니다.
     *
     * 계정을 만드는 것과 이벤트를 기록하는 것이 한 트랜잭션 안에 있어야 합니다.
     * 나뉘면 "계정은 생겼는데 프로필이 안 생기는" 상태가 만들어지고,
     * 그때 되돌리거나 다시 시도하는 장치를 따로 짜야 합니다.
     *
     * OutboxEventRecorder 는 트랜잭션이 없으면 예외를 던지도록 되어 있어
     * 이 애노테이션을 빠뜨리면 기동이 아니라 호출 시점에 바로 드러납니다.
     */
    @Transactional
    public AccountResponse signup(String email, String rawPassword, String nickname) {

        // 이메일 인증을 거쳤는지 확인합니다.
        //
        // 이것이 없으면 아무 이메일로나 가입할 수 있고,
        // 그 주소의 진짜 주인이 나중에 가입하려 할 때 이미 쓰인 이메일로 막힙니다.
        // 이메일을 계정 복구의 유일한 수단으로 삼기로 했으므로 그 수단이 남의 것이면 안 됩니다.
        if (!emailVerificationStore.isVerified(email)) {
            throw new CustomException(AuthErrorCode.EMAIL_NOT_VERIFIED);
        }

        // 이미 쓰이는 이메일인지 봅니다.
        //
        // 탈퇴한 계정도 참으로 나옵니다.
        // 행이 남아 있는 한 같은 이메일로 다시 가입할 수 없다는 뜻이며 그것이 의도입니다.
        // 탈퇴 이벤트를 각 서비스가 소비하는 동안 같은 주소로 새 계정이 생기면
        // 방금 만든 계정의 데이터가 지워지는 상황이 생길 수 있습니다.
        if (accountRepository.existsByEmail(email)) {
            throw new CustomException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
        }

        Account account = accountRepository.save(
                Account.createLocal(email, passwordEncoder.encode(rawPassword)));

        // 닉네임은 저장하지 않고 그대로 넘깁니다.
        // account 테이블에 그 컬럼이 없고 user_profile 이 소유자입니다.
        outboxEventRecorder.record(
                new AccountCreatedEvent(account.getId(), account.getEmail(), nickname));

        // 인증 표시를 지웁니다. 커밋이 끝난 뒤에 실행됩니다.
        //
        // 남겨 두면 같은 표시로 여러 번 가입을 시도할 수 있습니다.
        // 지금은 이메일이 중복될 수 없어 두 번째가 막히지만,
        // 표시의 뜻이 "이번 가입에 쓸 수 있다" 이므로 쓴 뒤에 지우는 것이 맞습니다.
        //
        // 여기서 바로 지우지 않는 이유는 아래 afterCommit 설명에 있습니다.
        afterCommitExecutor.run(() -> emailVerificationStore.clearVerified(email),
                "이메일 인증 표시 삭제");

        log.info("회원가입 완료 accountId={}", account.getId());
        return AccountResponse.from(account);
    }

    /**
     * 로그인 결과입니다.
     *
     * 컨트롤러가 토큰을 쿠키로 바꿔 심어야 하므로 발급된 토큰을 함께 돌려줍니다.
     * 쿠키를 만드는 일을 이 계층에서 하지 않는 것은 그것이 HTTP 의 사정이기 때문입니다.
     */
    public record LoginResult(AccountResponse account,
                              TokenProvider.IssuedToken accessToken,
                              TokenProvider.IssuedToken refreshToken) {
    }

    /**
     * 로그인입니다.
     *
     * @param ipAddress null 을 허용합니다. 무엇을 넣을지는 아직 정해지지 않았습니다.
     * @param userAgent 브라우저가 보낸 값이며 게이트웨이가 지우지 않으므로 그대로 도착합니다.
     */
    @Transactional
    public LoginResult login(String email, String rawPassword,
                             String ipAddress, String userAgent) {

        // 계정을 찾지 못한 경우와 비밀번호가 틀린 경우에 같은 코드를 씁니다.
        //
        // 나누면 "이 이메일은 가입되어 있다" 가 드러나 회원 목록을 캐낼 수 있습니다.
        // 아래 세 갈래가 전부 LOGIN_FAILED 로 모이는 것이 그 때문입니다.
        Account account = accountRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(AuthErrorCode.LOGIN_FAILED));

        // 소셜 계정도 같은 실패로 처리합니다.
        //
        // 비밀번호가 없다는 것을 따로 알려 주면 위에서 숨긴 것이 그대로 드러납니다.
        // 아무 비밀번호나 넣고 응답 코드만 비교해도
        // "그 이메일은 가입되어 있고 소셜 계정이다" 를 알 수 있기 때문입니다.
        // 계정을 하나 찾아냈다는 점에서 비밀번호가 틀린 경우와 다를 것이 없습니다.
        //
        // 판단을 hasPassword 로 하는 것은 비밀번호를 쓰는 제공자가 늘어나도 그대로 맞기 때문입니다.
        //
        // 소셜로 가입한 사람이 이유를 모르는 것은 화면이 풀어야 할 몫입니다.
        // 로그인 화면에 소셜 로그인 버튼이 함께 있으므로 그쪽으로 넘어가면 됩니다.
        if (!account.getAuthProvider().hasPassword()) {
            log.debug("소셜 계정에 비밀번호 로그인을 시도했습니다. accountId={}", account.getId());
            throw new CustomException(AuthErrorCode.LOGIN_FAILED);
        }

        if (!passwordEncoder.matches(rawPassword, account.getPasswordHash())) {
            throw new CustomException(AuthErrorCode.LOGIN_FAILED);
        }

        // 탈퇴한 계정은 그 사실을 알려 줍니다.
        //
        // 위의 셋과 달리 숨기지 않는 이유는, 이유를 모르면 사용자가
        // 비밀번호를 계속 다시 입력하게 되기 때문입니다.
        // 계정이 있다는 것은 이미 본인이 아는 사실입니다.
        if (!account.canLogin()) {
            throw new CustomException(AuthErrorCode.ACCOUNT_WITHDRAWN);
        }

        TokenProvider.IssuedToken accessToken =
                tokenProvider.issueAccessToken(account.getId(), account.getRole());
        TokenProvider.IssuedToken refreshToken =
                tokenProvider.issueRefreshToken(account.getId(), account.getRole());

        // 리프레시 토큰을 두 곳에 남깁니다. 목적이 다릅니다.
        //
        //   저장소   아직 쓸 수 있는 토큰인가를 판단합니다. 로그아웃하면 지웁니다
        //   이력     언제 어디서 발급됐는가를 남깁니다. 지우지 않습니다
        //
        // 저장소 쪽만 커밋 뒤로 미룹니다. 이력은 데이터베이스라 트랜잭션에 함께 묶입니다.
        Duration refreshTtl = Duration.between(Instant.now(), refreshToken.expiresAt());
        afterCommitExecutor.run(
                () -> refreshTokenStore.save(refreshToken.tokenId(), account.getId(), refreshTtl),
                "리프레시 토큰 저장");

        // 이 로그인 한 번을 묶는 값을 여기서 만듭니다.
        //
        // 갱신할 때마다 리프레시 토큰이 교체되면서 이력에 행이 하나씩 쌓이는데,
        // 그 행들은 이 값을 그대로 물려받아 한 사슬이 됩니다.
        // 없으면 기기가 둘 이상일 때 어느 행이 어느 로그인에서 시작됐는지 알 수 없고,
        // 마지막 행의 발급 시각은 로그인 시각이 아니라 마지막 갱신 시각입니다.
        //
        // 인증 판단에는 쓰이지 않습니다. 이력을 사람이 읽을 때만 쓰는 값입니다.
        UUID loginId = UUID.randomUUID();

        refreshTokenLogRepository.save(RefreshTokenLog.issue(
                account.getId(),
                loginId,
                refreshToken.tokenId(),
                toLocalDateTime(Instant.now()),
                toLocalDateTime(refreshToken.expiresAt()),
                ipAddress,
                userAgent));

        account.updateLastLoginAt(LocalDateTime.now());

        log.info("로그인 성공 accountId={}", account.getId());
        return new LoginResult(AccountResponse.from(account), accessToken, refreshToken);
    }

    // 토큰은 Instant 로 시각을 다루고 엔티티는 LocalDateTime 을 씁니다.
    //
    // 컨테이너 시간대를 서울로 고정해 두었으므로 시스템 기본 시간대로 변환하면 맞습니다.
    // 그 설정이 빠지면 아홉 시간이 어긋나는데 timestamp 컬럼이라 데이터베이스가 바로잡지 않습니다.
    private LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
