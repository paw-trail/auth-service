package com.pawtrail.auth.application.service;

import com.pawtrail.auth.application.support.AfterCommitExecutor;
import com.pawtrail.auth.application.support.VerificationCodeGenerator;
import com.pawtrail.auth.domain.event.payload.AccountWithdrawnEvent;
import com.pawtrail.auth.domain.exception.AuthErrorCode;
import com.pawtrail.auth.domain.model.Account;
import com.pawtrail.auth.domain.provider.MailSender;
import com.pawtrail.auth.domain.repository.AccountRepository;
import com.pawtrail.auth.domain.repository.SendRateLimitStore;
import com.pawtrail.auth.domain.repository.WithdrawStore;
import com.pawtrail.common.exception.CustomException;
import com.pawtrail.common.message.outbox.OutboxEventRecorder;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴를 처리합니다.
 *
 * 코드를 보내는 일과 그 코드로 하는 일을 한 클래스가 가집니다.
 * PasswordResetService 와 같은 모양입니다. 그쪽도 코드 발송과 비밀번호 변경을 함께 가집니다.
 * 둘을 갈라 두면 같은 흐름의 앞뒤가 다른 파일에 놓여 한쪽만 고치기 쉬워집니다.
 *
 * AccountService 에 넣지 않은 이유
 *
 * 그 클래스는 계정 식별자를 받아 자기 것을 보고 고치는 일을 모아 둔 곳입니다.
 * 탈퇴에는 메일 발송과 이벤트 발행이 붙는데, 그것들은 성격이 다른 일이고
 * 넣으면 그 클래스가 주입받는 것이 세 개에서 여덟 개로 늘어납니다.
 *
 * 본인 확인을 메일 코드로 하는 이유
 *
 * 비밀번호로는 계정 종류에 따라 확인할 수 있는 사람과 없는 사람이 갈립니다.
 * 소셜 계정은 비밀번호를 가지지 않기 때문입니다.
 * 이메일은 어느 쪽이든 반드시 있고, 브라우저에 저장될 수 있는 비밀번호와 달리
 * 메일함은 따로 로그인해야 열립니다.
 *
 * 이 서비스는 계정 존재를 숨기지 않습니다.
 * 두 경로 모두 로그인한 사람만 부를 수 있고 자기 계정만 다루므로 숨길 것이 없습니다.
 * 재설정 쪽이 모든 실패를 성공으로 응답하는 것과 갈리는 지점입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawService {

    // 코드를 틀릴 수 있는 횟수임
    // 여섯 자리 숫자는 백만 가지뿐이라 무차별 대입이 실제로 가능하므로 횟수를 셈
    private static final int MAX_ATTEMPT = 5;

    private final AccountRepository accountRepository;
    private final WithdrawStore withdrawStore;
    private final SendRateLimitStore sendRateLimitStore;
    private final VerificationCodeGenerator codeGenerator;
    private final MailSender mailSender;
    private final AfterCommitExecutor afterCommitExecutor;
    private final OutboxEventRecorder outboxEventRecorder;
    private final TokenRevokeService tokenRevokeService;

    /**
     * 탈퇴 인증 코드를 계정에 등록된 주소로 보냅니다.
     *
     * 이메일을 요청에서 받지 않고 계정에서 꺼냅니다.
     * 받으면 남의 주소를 넣어 메일을 보내게 할 수 있고,
     * 로그인한 사람의 주소는 우리가 이미 알고 있으므로 받을 이유가 없습니다.
     *
     * 발송 제한을 겁니다.
     *
     * 메일을 쏟는 것을 막으려는 것이 아닙니다.
     * 이 경로는 로그인해야 부를 수 있고 자기 주소로만 가므로 그 걱정은 성립하지 않습니다.
     *
     * 막으려는 것은 시도 횟수 제한이 무력화되는 것입니다.
     * 코드를 다시 보내면 저장소가 코드를 덮어쓰면서 틀린 횟수를 0으로 되돌리므로,
     * 발송이 자유로우면 네 번 찍어보고 다시 보내는 것을 반복해
     * 다섯 번이라는 상한이 아무것도 막지 못하게 됩니다.
     *
     * 한도는 용도별로 따로 셉니다. 가입 인증을 몇 번 받았다고 탈퇴가 막히면 안 됩니다.
     *
     * @throws CustomException 계정이 없거나, 이미 탈퇴했거나, 발송이 잦거나, 발송에 실패한 경우입니다.
     */
    @Transactional(readOnly = true)
    public void sendCode(UUID accountId) {
        Account account = findActiveAccount(accountId);
        String email = account.getEmail();

        // 보낼 자리를 먼저 잡음
        //
        // 묻지 않고 잡는 이유는, 물어보기만 하면 그 뒤 발송에 걸리는 몇 초 동안
        // 저장소에 흔적이 없어 동시에 들어온 요청이 전부 통과하기 때문임
        //
        // 코드를 저장하기 전에 잡아야 함
        // 순서가 반대면 제한에 걸린 요청도 이미 코드를 덮어써 틀린 횟수를 되돌린 뒤임
        if (!sendRateLimitStore.tryAcquire(MailSender.MailPurpose.WITHDRAW, email)) {
            throw new CustomException(AuthErrorCode.MAIL_SEND_COOLDOWN);
        }

        String code = codeGenerator.generate();
        withdrawStore.saveCode(email, code);

        // 발송 실패를 그대로 알려 줌
        //
        // 재설정 쪽은 실패까지 삼키는데 거기는 계정 존재를 숨겨야 하기 때문임
        // 여기는 이미 로그인한 사람이라 숨길 것이 없고,
        // 오지 않는 코드를 기다리게 하는 것보다 실패를 아는 편이 나음
        try {
            mailSender.sendVerificationCode(email, code, MailSender.MailPurpose.WITHDRAW);
        } catch (RuntimeException e) {
            // 보내지 못했으므로 잡아 둔 자리를 돌려줌
            // 그러지 않으면 메일이 오지도 않았는데 다음 요청이 쿨다운에 막힘
            sendRateLimitStore.release(MailSender.MailPurpose.WITHDRAW, email);
            throw e;
        }

        // 보낸 뒤에 기록함
        // 실패한 것까지 세면 메일 서버가 잠시 죽었을 때 정상 사용자가 한도를 다 쓰고 막힘
        sendRateLimitStore.recordSent(MailSender.MailPurpose.WITHDRAW, email);

        log.info("탈퇴 인증 코드를 보냈습니다. accountId={}", accountId);
    }

    /**
     * 코드를 확인하고 탈퇴시킵니다.
     *
     * 확인과 탈퇴가 한 요청인 것은 재설정과 같은 이유입니다.
     * 나누면 "코드를 맞혔다" 를 어딘가 남겨야 하고, 그 표시가 새어 나가면
     * 코드를 모르는 사람이 계정을 지울 수 있습니다.
     *
     * 계정을 지우지 않고 상태만 바꿉니다.
     * 실제 데이터 삭제는 account.withdrawn 을 받은 각 서비스가 자기 몫을 합니다.
     * 행을 남기는 것은 이벤트 소비가 실패했을 때 "이 식별자가 정말 탈퇴한 것이 맞는지" 를
     * 확인할 근거가 필요하고, 발급 이력이 가리키는 대상이 사라지면 안 되기 때문입니다.
     *
     * @throws CustomException 코드가 틀렸거나, 시도 횟수를 넘겼거나, 이미 탈퇴한 경우입니다.
     */
    @Transactional
    public void withdraw(UUID accountId, String inputCode) {
        Account account = findActiveAccount(accountId);
        String email = account.getEmail();

        verifyCode(email, inputCode);

        // 탈퇴 처리
        //
        // 상태를 바꾸면서 이메일과 제공자 식별자를 함께 끊음
        // 그래야 같은 이메일과 같은 소셜 계정으로 다시 가입할 수 있음
        // 끊지 않으면 한 번 탈퇴한 사람이 그 주소로 영영 돌아오지 못함
        account.withdraw();

        // 이벤트를 같은 트랜잭션에 기록함
        //
        // 상태 변경과 이벤트가 나뉘면 "탈퇴는 됐는데 데이터는 남는" 상태가 만들어지고,
        // 그때 되돌리거나 다시 시도하는 장치를 따로 짜야 함
        // OutboxEventRecorder 는 트랜잭션이 없으면 예외를 던지므로
        // 위 애노테이션을 빠뜨리면 기동이 아니라 호출 시점에 바로 드러남
        outboxEventRecorder.record(new AccountWithdrawnEvent(accountId));

        // 발급된 토큰을 전부 무효로 만듦
        //
        // 같은 트랜잭션에서 돌아야 함
        // 새 트랜잭션으로 하면 계정을 데이터베이스에서 다시 읽어 고치는데,
        // 여기 있는 인스턴스가 나중에 갱신되면서 그 값을 옛것으로 덮음
        //
        // 이미 나가 있는 액세스 토큰은 이것으로 막히지 않음
        // 게이트웨이가 서명만 보고 통과시키므로 만료될 때까지(30분) 그대로 쓰임
        // 다만 그 토큰으로 할 수 있는 일이 조회뿐이고 갱신은 여기서 끊김
        tokenRevokeService.revokeAll(accountId, "회원 탈퇴");

        // 코드를 지움, 커밋 뒤에 실행됨
        //
        // Redis 는 롤백 대상이 아니라 여기서 바로 지우면
        // 탈퇴가 실패했을 때 코드만 사라져 사용자가 다시 요청해야 함
        afterCommitExecutor.run(() -> withdrawStore.deleteCode(email), "탈퇴 코드 삭제");

        log.info("탈퇴를 처리했습니다. accountId={}", accountId);
    }

    /**
     * 헤더의 식별자로 계정을 찾고 탈퇴하지 않았는지 봅니다.
     */
    private Account findActiveAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> {
                    // 게이트웨이가 검증한 토큰에서 나온 값이라 정상 흐름에서는 나오지 않음
                    log.warn("헤더의 계정을 찾지 못했습니다. accountId={}", accountId);
                    return new CustomException(AuthErrorCode.ACCOUNT_NOT_FOUND);
                });

        // 이미 탈퇴한 계정임
        //
        // 탈퇴 전에 발급된 액세스 토큰이 아직 살아 있으면 여기까지 올 수 있음
        // 두 번 탈퇴시키면 이벤트가 또 나가고 소비자들이 없는 데이터를 지우려 함
        if (!account.canLogin()) {
            throw new CustomException(AuthErrorCode.ACCOUNT_WITHDRAWN);
        }
        return account;
    }

    /**
     * 코드를 대조하고 틀린 횟수를 셉니다.
     *
     * 코드가 없는 경우와 틀린 경우에 같은 코드를 내보냅니다.
     * 나누면 "요청한 적이 있는지" 가 드러나는데, 그것을 알아서 좋을 사람이 없습니다.
     */
    private void verifyCode(String email, String inputCode) {
        String saved = withdrawStore.findCode(email)
                .orElseThrow(() -> new CustomException(AuthErrorCode.INVALID_VERIFICATION_CODE));

        if (saved.equals(inputCode)) {
            return;
        }

        int attempt = withdrawStore.increaseAttempt(email);
        if (attempt >= MAX_ATTEMPT) {
            // 횟수를 넘기면 코드를 지움
            // 잠갔다 푸는 방식을 쓰지 않는 것은 해제 시각을 따로 관리해야 하고,
            // 다시 요청하면 새 코드가 오므로 사용자가 막히지도 않기 때문임
            withdrawStore.deleteCode(email);
            log.warn("탈퇴 시도 횟수를 넘겨 코드를 폐기했습니다. email 은 남기지 않음");
            throw new CustomException(AuthErrorCode.TOO_MANY_VERIFICATION_ATTEMPTS);
        }
        throw new CustomException(AuthErrorCode.INVALID_VERIFICATION_CODE);
    }
}
