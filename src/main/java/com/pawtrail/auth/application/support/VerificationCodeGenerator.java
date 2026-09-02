package com.pawtrail.auth.application.support;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 인증 코드를 만듭니다.
 *
 * 가입 인증과 비밀번호 재설정이 같은 것을 씁니다.
 * 두 기능이 저장소는 나눠 쓰지만 코드 자체는 똑같으므로 여기서 한 번만 만듭니다.
 *
 * 왜 domain 이 아니라 여기인가
 *
 * domain 에는 이 서비스의 개념과 규칙이 들어갑니다.
 * 그런데 "여섯 자리 난수" 는 무엇이 옳은지를 판단하는 규칙이 아니라 값을 만드는 도구입니다.
 * 판정 규칙 같은 것이 domain 에 클래스로 들어가는 것과는 성격이 다릅니다.
 *
 * AfterCommitExecutor 와 같은 자리에 둔 것은 둘의 성격이 같기 때문입니다.
 * 아무것도 판단하지 않고 여러 서비스가 함께 쓰는 도구입니다.
 */
@Component
public class VerificationCodeGenerator {

    // 여섯 자리로 두는 이유는 사람이 옮겨 적을 수 있는 길이이기 때문임
    // 그 대신 백만 가지뿐이라 무차별 대입이 가능하므로 시도 횟수 제한이 반드시 함께 있어야 함
    private static final int CODE_LENGTH = 6;
    private static final int BOUND = 1_000_000;

    // Random 이 아니라 SecureRandom 을 씁니다.
    //
    // Random 은 시드를 알면 다음 값이 예측됩니다.
    // 인증 코드는 맞히면 남의 계정을 가져가는 값이므로 예측 가능해서는 안 됩니다.
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 여섯 자리 코드를 만듭니다.
     *
     * 앞자리가 0 이어도 그대로 둡니다.
     * 0 을 피하면 경우의 수가 줄고, 문자열로 다루므로 자릿수가 흐트러지지 않습니다.
     */
    public String generate() {
        return String.format("%0" + CODE_LENGTH + "d", RANDOM.nextInt(BOUND));
    }
}
