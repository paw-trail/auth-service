package com.pawtrail.auth.application.service;

import com.pawtrail.auth.application.dto.input.LoginInput;
import com.pawtrail.auth.application.dto.input.SignupInput;
import com.pawtrail.auth.application.dto.output.AccountOutput;
import com.pawtrail.auth.application.support.AfterCommitExecutor;
import com.pawtrail.auth.domain.event.payload.AccountCreatedEvent;
import com.pawtrail.auth.domain.exception.AuthErrorCode;
import com.pawtrail.auth.domain.model.Account;
import com.pawtrail.auth.domain.repository.AccountRepository;
import com.pawtrail.auth.domain.repository.EmailVerificationStore;
import com.pawtrail.auth.infrastructure.security.TokenProvider;
import com.pawtrail.common.exception.CustomException;
import com.pawtrail.common.message.outbox.OutboxEventRecorder;
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
    private final PasswordEncoder passwordEncoder;
    private final OutboxEventRecorder outboxEventRecorder;
    private final EmailVerificationStore emailVerificationStore;
    private final AfterCommitExecutor afterCommitExecutor;
    private final TokenIssueService tokenIssueService;

    /**
     * 회원가입입니다.
     *
     * 가입이 끝나면 곧바로 로그인시킵니다.
     * 프론트의 가입 흐름이 계정 만들기와 반려동물 등록의 두 단계인데,
     * 반려동물 등록은 인증이 필요한 요청입니다. 여기서 토큰을 발급하지 않으면
     * 그 요청이 401 로 막히고, 사용자는 방금 정한 비밀번호로 한 번 더 들어와야 합니다.
     * 소셜 로그인은 처음부터 콜백에서 쿠키를 심고 있어 로컬 가입만 흐름이 길었습니다.
     *
     * 로그인 결과와 같은 것을 돌려주는 것은 그 뒤가 로그인과 완전히 같기 때문입니다.
     * 컨트롤러가 쿠키를 심는 코드도 로그인과 같습니다.
     *
     * 계정을 만드는 것과 이벤트를 기록하는 것이 한 트랜잭션 안에 있어야 합니다.
     * 나뉘면 "계정은 생겼는데 프로필이 안 생기는" 상태가 만들어지고,
     * 그때 되돌리거나 다시 시도하는 장치를 따로 짜야 합니다.
     *
     * OutboxEventRecorder 는 트랜잭션이 없으면 예외를 던지도록 되어 있어
     * 이 애노테이션을 빠뜨리면 기동이 아니라 호출 시점에 바로 드러납니다.
     */
    @Transactional
    public LoginResult signup(SignupInput input) {
        String email = input.email();

        // 이메일 인증을 거쳤는지 확인함
        //
        // 이것이 없으면 아무 이메일로나 가입할 수 있고,
        // 그 주소의 진짜 주인이 나중에 가입하려 할 때 이미 쓰인 이메일로 막힙니다.
        // 이메일을 계정 복구의 유일한 수단으로 삼기로 했으므로 그 수단이 남의 것이면 안 됩니다.
        if (!emailVerificationStore.isVerified(email)) {
            throw new CustomException(AuthErrorCode.EMAIL_NOT_VERIFIED);
        }

        // 이미 쓰이는 이메일인지 봅니다.
        //
        // 탈퇴한 계정은 여기에 걸리지 않음
        // 탈퇴할 때 email 을 치환하므로 그 주소로 다시 가입할 수 있음
        //
        // 한때 그것을 막아 두었던 이유는, 탈퇴 이벤트를 각 서비스가 소비하는 동안
        // 같은 주소로 새 계정이 생기면 방금 만든 계정의 데이터가 지워질 수 있다는 것이었음
        // 그 우려는 성립하지 않음. 이벤트가 나르는 값이 계정 식별자 하나뿐이고
        // 받는 쪽이 전부 그 값으로 지우므로, 새 식별자를 받은 계정은 대상이 될 수 없음
        if (accountRepository.existsByEmail(email)) {
            throw new CustomException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
        }

        Account account = accountRepository.save(
                Account.createLocal(email, passwordEncoder.encode(input.password())));

        // 닉네임은 저장하지 않고 그대로 넘깁니다.
        // account 테이블에 그 컬럼이 없고 user_profile 이 소유자임
        outboxEventRecorder.record(
                new AccountCreatedEvent(account.getId(), account.getEmail(), input.nickname()));

        // 인증 표시를 지웁니다. 커밋이 끝난 뒤에 실행됩니다.
        //
        // 남겨 두면 같은 표시로 여러 번 가입을 시도할 수 있음
        // 지금은 이메일이 중복될 수 없어 두 번째가 막히지만,
        // 표시의 뜻이 "이번 가입에 쓸 수 있다" 이므로 쓴 뒤에 지우는 것이 맞음
        //
        // 여기서 바로 지우지 않는 이유는 아래 afterCommit 설명에 있음
        afterCommitExecutor.run(() -> emailVerificationStore.clearVerified(email),
                "이메일 인증 표시 삭제");

        // 가입 직후의 로그인 한 번을 엶
        //
        // 로그인과 같은 메서드라 토큰 발급, 이력 기록, 마지막 로그인 시각 갱신이 함께 일어남
        // refresh_token_log 에 가입 시점 행이 하나 생기는데, login_id 로 가입 세션이 묶이므로
        // "가입" 과 "첫 로그인" 이 구분되지 않던 이력이 오히려 정확해짐
        TokenIssueService.IssuedSession session =
                tokenIssueService.issue(account, input.client());

        log.info("회원가입 완료 accountId={}", account.getId());
        return new LoginResult(AccountOutput.from(account),
                session.accessToken(), session.refreshToken());
    }

    /**
     * 로그인 결과입니다. 가입 직후의 자동 로그인도 이것을 돌려줍니다.
     *
     * 컨트롤러가 토큰을 쿠키로 바꿔 심어야 하므로 발급된 토큰을 함께 돌려줍니다.
     * 쿠키를 만드는 일을 이 계층에서 하지 않는 것은 그것이 HTTP 의 사정이기 때문입니다.
     */
    public record LoginResult(AccountOutput account,
                              TokenProvider.IssuedToken accessToken,
                              TokenProvider.IssuedToken refreshToken) {
    }

    /**
     * 로그인입니다.
     *
     */
    @Transactional
    public LoginResult login(LoginInput input) {
        String email = input.email();

        // 계정을 찾지 못한 경우와 비밀번호가 틀린 경우에 같은 코드를 씁니다.
        //
        // 나누면 "이 이메일은 가입되어 있다" 가 드러나 회원 목록을 캐낼 수 있음
        // 아래 세 갈래가 전부 LOGIN_FAILED 로 모이는 것이 그 때문임
        Account account = accountRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(AuthErrorCode.LOGIN_FAILED));

        // 소셜 계정도 같은 실패로 처리함
        //
        // 비밀번호가 없다는 것을 따로 알려 주면 위에서 숨긴 것이 그대로 드러납니다.
        // 아무 비밀번호나 넣고 응답 코드만 비교해도
        // "그 이메일은 가입되어 있고 소셜 계정이다" 를 알 수 있기 때문임
        // 계정을 하나 찾아냈다는 점에서 비밀번호가 틀린 경우와 다를 것이 없음
        //
        // 판단을 hasPassword 로 하는 것은 비밀번호를 쓰는 제공자가 늘어나도 그대로 맞기 때문임
        //
        // 소셜로 가입한 사람이 이유를 모르는 것은 화면이 풀어야 할 몫임
        // 로그인 화면에 소셜 로그인 버튼이 함께 있으므로 그쪽으로 넘어가면 됩니다.
        if (!account.getAuthProvider().hasPassword()) {
            log.debug("소셜 계정에 비밀번호 로그인을 시도했습니다. accountId={}", account.getId());
            throw new CustomException(AuthErrorCode.LOGIN_FAILED);
        }

        if (!passwordEncoder.matches(input.password(), account.getPasswordHash())) {
            throw new CustomException(AuthErrorCode.LOGIN_FAILED);
        }

        // 탈퇴한 계정은 그 사실을 알려 줍니다.
        //
        // 위의 셋과 달리 숨기지 않는 이유는, 이유를 모르면 사용자가
        // 비밀번호를 계속 다시 입력하게 되기 때문임
        // 계정이 있다는 것은 이미 본인이 아는 사실임
        if (!account.canLogin()) {
            throw new CustomException(AuthErrorCode.ACCOUNT_WITHDRAWN);
        }

        // 확인이 끝났으므로 로그인 한 번을 엶
        //
        // 토큰 발급, 저장소와 이력 기록, 마지막 로그인 시각 갱신이 그 안에 있음
        // 소셜 로그인도 사람을 찾는 방법만 다르고 이 뒤는 같아서 한곳에 모아 둠
        TokenIssueService.IssuedSession session =
                tokenIssueService.issue(account, input.client());

        log.info("로그인 성공 accountId={}", account.getId());
        return new LoginResult(AccountOutput.from(account),
                session.accessToken(), session.refreshToken());
    }
}
