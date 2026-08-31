package com.pawtrail.auth.application.service;

import com.pawtrail.auth.application.support.AfterCommitExecutor;
import com.pawtrail.auth.application.support.VerificationCodeGenerator;
import com.pawtrail.auth.domain.exception.AuthErrorCode;
import com.pawtrail.auth.domain.model.Account;
import com.pawtrail.auth.domain.provider.MailSender;
import com.pawtrail.auth.domain.repository.AccountRepository;
import com.pawtrail.auth.domain.repository.PasswordResetStore;
import com.pawtrail.common.exception.CustomException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호를 잊은 사람이 새로 정하도록 돕습니다.
 *
 * 로그인 상태에서 바꾸는 것과 다른 기능입니다.
 * 그쪽은 현재 비밀번호로 본인을 확인하지만, 여기는 그것을 모르는 상태라
 * 메일이 본인 확인 수단입니다.
 * 둘을 합치면 이메일만 알아도 남의 비밀번호를 바꿀 수 있게 됩니다.
 *
 * 이 서비스의 규칙은 하나로 요약됩니다.
 * 어떤 경우에도 그 이메일이 가입되어 있는지를 드러내지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final int MAX_ATTEMPT = 5;

    private final AccountRepository accountRepository;
    private final PasswordResetStore passwordResetStore;
    private final VerificationCodeGenerator codeGenerator;
    private final PasswordEncoder passwordEncoder;
    private final MailSender mailSender;
    private final AfterCommitExecutor afterCommitExecutor;

    /**
     * 재설정 코드를 보냅니다.
     *
     * 가입되지 않은 이메일이어도, 소셜 계정이어도, 발송이 실패해도
     * 부르는 쪽에는 아무것도 알리지 않습니다.
     * 응답이 갈리면 그 차이만으로 회원 목록을 캐낼 수 있습니다.
     */
    public void sendCode(String email) {

        Optional<Account> found = accountRepository.findByEmail(email);

        // 대상이 아닌 세 경우를 여기서 조용히 접습니다.
        //   계정이 없음
        //   탈퇴한 계정
        //   소셜 계정이라 비밀번호가 없음
        if (found.isEmpty()
                || !found.get().canLogin()
                || !found.get().getAuthProvider().hasPassword()) {
            log.info("재설정 대상이 아닌 요청입니다. 응답은 성공으로 보냅니다.");
            return;
        }

        String code = codeGenerator.generate();
        passwordResetStore.saveCode(email, code);

        // 발송 실패도 삼킵니다.
        //
        // 가입 인증과 정반대인데, 거기는 실패를 알려 주는 편이 낫지만
        // 여기서 500 을 내보내면 그것만으로 "이 이메일은 가입돼 있다" 가 드러납니다.
        // 없는 이메일은 위에서 이미 돌아가 발송 자체를 시도하지 않기 때문입니다.
        try {
            mailSender.sendVerificationCode(email, code, MailSender.MailPurpose.PASSWORD_RESET);
        } catch (Exception e) {
            log.error("재설정 메일 발송에 실패했습니다. 응답은 성공으로 보냅니다.", e);
        }
    }

    /**
     * 코드를 확인하고 비밀번호를 바꿉니다.
     *
     * 확인과 변경이 한 요청인 것은, 나누면 "코드를 맞혔다" 를 어딘가 남겨야 하고
     * 그 표시가 새어 나가면 코드를 모르는 사람이 비밀번호를 바꿀 수 있기 때문입니다.
     * 가입 인증이 표시를 남기는 것과 갈리는 지점입니다.
     */
    @Transactional
    public void reset(String email, String inputCode, String newRawPassword) {

        String saved = passwordResetStore.findCode(email)
                .orElseThrow(() -> new CustomException(AuthErrorCode.INVALID_VERIFICATION_CODE));

        if (!saved.equals(inputCode)) {
            int attempt = passwordResetStore.increaseAttempt(email);
            if (attempt >= MAX_ATTEMPT) {
                passwordResetStore.deleteCode(email);
                log.warn("재설정 시도 횟수를 넘겨 코드를 폐기했습니다.");
                throw new CustomException(AuthErrorCode.TOO_MANY_VERIFICATION_ATTEMPTS);
            }
            throw new CustomException(AuthErrorCode.INVALID_VERIFICATION_CODE);
        }

        // 코드를 맞힌 시점에서 본인 확인은 끝났습니다.
        //
        // 여기서 계정을 못 찾는 것은 코드를 보낸 뒤에 탈퇴한 경우뿐이라 드뭅니다.
        // 그때는 숨길 이유가 없으므로 그대로 알려 줍니다.
        Account account = accountRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(AuthErrorCode.ACCOUNT_NOT_FOUND));

        account.changePassword(passwordEncoder.encode(newRawPassword));

        // 코드를 지웁니다. 한 번 쓰면 없어지는 값입니다.
        //
        // 커밋 뒤에 지우는 것은 Redis 가 롤백되지 않기 때문입니다.
        // 여기서 바로 지우면 비밀번호 변경이 실패했을 때 코드만 사라져
        // 사용자가 다시 요청해야 합니다.
        afterCommitExecutor.run(() -> passwordResetStore.deleteCode(email), "재설정 코드 삭제");

        log.info("비밀번호를 재설정했습니다. accountId={}", account.getId());
    }
}
