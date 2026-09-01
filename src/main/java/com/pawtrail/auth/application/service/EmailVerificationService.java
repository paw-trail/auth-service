package com.pawtrail.auth.application.service;

import com.pawtrail.auth.application.support.VerificationCodeGenerator;
import com.pawtrail.auth.domain.exception.AuthErrorCode;
import com.pawtrail.auth.domain.provider.MailSender;
import com.pawtrail.auth.domain.repository.AccountRepository;
import com.pawtrail.auth.domain.repository.EmailVerificationStore;
import com.pawtrail.auth.domain.repository.SendRateLimitStore;
import com.pawtrail.common.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 회원가입 이메일 인증을 처리합니다.
 *
 * 코드를 보내고, 맞히면 통과 표시를 남깁니다.
 * 그 표시를 회원가입이 확인하므로 두 요청이 이어집니다.
 *
 * 트랜잭션이 없습니다.
 * 하는 일이 Redis 읽고 쓰기와 메일 발송뿐이라 데이터베이스를 건드리지 않습니다.
 * 계정 조회는 읽기 한 번이라 굳이 묶을 이유가 없습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    // 코드를 틀릴 수 있는 횟수입니다.
    //
    // 여섯 자리는 백만 가지라 다섯 번으로는 맞힐 수 없습니다.
    // 더 줄이면 오타 두세 번에 막히는 정상 사용자가 늘어납니다.
    private static final int MAX_ATTEMPT = 5;

    private final AccountRepository accountRepository;
    private final EmailVerificationStore emailVerificationStore;
    private final SendRateLimitStore sendRateLimitStore;
    private final VerificationCodeGenerator codeGenerator;
    private final MailSender mailSender;

    /**
     * 인증 코드를 보냅니다.
     *
     * 이미 쓰는 이메일이면 그 사실을 알려 줍니다.
     * 비밀번호 재설정과 정반대인데, 거기는 계정 존재를 숨겨야 하지만
     * 가입은 알려 주지 않으면 사용자가 왜 진행이 안 되는지 알 수 없습니다.
     * 어차피 가입을 시도하면 드러날 사실이기도 합니다.
     */
    public void sendCode(String email) {

        // 탈퇴한 계정도 참으로 나옵니다.
        // 행이 남아 있는 한 같은 이메일로 다시 가입할 수 없다는 뜻이며 그것이 의도입니다.
        if (accountRepository.existsByEmail(email)) {
            throw new CustomException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // 보낼 자리를 잡습니다. 잡지 못하면 보내지 않습니다.
        //
        // 이 경로는 인증이 필요 없어 누구나 계속 부를 수 있습니다.
        // 여기서 막지 않으면 남의 주소로 메일이 쏟아지고,
        // 하루 발송 한도가 소진되어 정상 사용자도 메일을 못 받게 됩니다.
        //
        // 묻지 않고 잡는 이유는, 물어보기만 하면 그 뒤 발송에 걸리는 몇 초 동안
        // 저장소에 흔적이 없어 동시에 들어온 요청이 전부 통과하기 때문입니다.
        //
        // 가입 인증은 이미 위에서 계정 존재 여부를 알려 주고 있으므로
        // 제한 사실을 숨길 이유가 없습니다. 그대로 알려 줍니다.
        if (!sendRateLimitStore.tryAcquire(MailSender.MailPurpose.SIGNUP, email)) {
            throw new CustomException(AuthErrorCode.MAIL_SEND_COOLDOWN);
        }

        String code = codeGenerator.generate();
        emailVerificationStore.saveCode(email, code);

        try {
            // 발송이 실패하면 예외가 그대로 올라가 500 이 나갑니다.
            // 사용자가 오지 않는 코드를 기다리는 것보다 실패를 아는 편이 낫습니다.
            mailSender.sendVerificationCode(email, code, MailSender.MailPurpose.SIGNUP);

        } catch (RuntimeException e) {
            // 보내지 못했으므로 잡아 둔 자리를 돌려줍니다.
            // 그러지 않으면 메일이 오지도 않았는데 다음 시도가 쿨다운에 막힙니다.
            sendRateLimitStore.release(MailSender.MailPurpose.SIGNUP, email);
            throw e;
        }

        // 보낸 뒤에 기록합니다.
        //
        // 발송을 시도조차 못 한 경우까지 세면
        // 메일 서버가 잠깐 죽었을 때 정상 사용자가 한도를 다 쓰고 막힙니다.
        sendRateLimitStore.recordSent(MailSender.MailPurpose.SIGNUP, email);
    }

    /**
     * 코드를 확인하고 통과 표시를 남깁니다.
     */
    public void verify(String email, String inputCode) {

        String saved = emailVerificationStore.findCode(email)
            .orElseThrow(() -> new CustomException(AuthErrorCode.INVALID_VERIFICATION_CODE));

        if (!saved.equals(inputCode)) {
            int attempt = emailVerificationStore.increaseAttempt(email);

            // 횟수를 넘기면 코드를 지웁니다. 다시 요청해야 합니다.
            //
            // 잠갔다 푸는 방식을 쓰지 않는 것은 해제 시각을 따로 관리해야 하고,
            // 다시 요청하면 새 코드가 오므로 사용자가 막히지 않기 때문입니다.
            if (attempt >= MAX_ATTEMPT) {
                emailVerificationStore.deleteCode(email);
                log.warn("인증 시도 횟수를 넘겨 코드를 폐기했습니다.");
                throw new CustomException(AuthErrorCode.TOO_MANY_VERIFICATION_ATTEMPTS);
            }
            throw new CustomException(AuthErrorCode.INVALID_VERIFICATION_CODE);
        }

        // 맞혔으므로 코드를 지우고 통과 표시를 남깁니다.
        //
        // 코드를 남겨 두면 같은 코드로 여러 번 통과할 수 있습니다.
        // 지금 구조에서는 표시를 다시 남기는 것뿐이라 해가 없지만,
        // 한 번 쓰면 없어지는 것이 이 값의 뜻입니다.
        emailVerificationStore.deleteCode(email);
        emailVerificationStore.markVerified(email);
    }
}
