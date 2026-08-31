package com.pawtrail.auth.domain.repository;

import java.util.Optional;

/**
 * 회원가입 이메일 인증 상태를 보관하는 약속입니다.
 *
 * 두 가지를 나눠 담습니다.
 *
 *   보낸 코드   emailverify:{email}     10분,  시도 횟수를 함께 셈
 *   통과 표시   emailverified:{email}   30분,  가입에 성공하면 지움
 *
 * 표시를 따로 두는 이유는 인증과 가입이 별개 요청이기 때문입니다.
 * "이 이메일은 방금 확인됐다" 를 어딘가 남기지 않으면
 * 인증을 건너뛰고 가입만 직접 부를 수 있습니다.
 * 코드를 맞힌 사람과 가입하는 사람이 같다는 것을 잇는 것이 이 표시입니다.
 *
 * 데이터베이스 테이블을 만들지 않는 이유는 만료가 이 값의 본질이기 때문입니다.
 * 시간이 지나면 사라져야 하는데, 테이블에 두면 지우는 작업을 따로 만들어야 하고
 * 남길 가치도 없는 이력이 쌓입니다.
 */
public interface EmailVerificationStore {

    /**
     * 보낸 코드를 저장합니다. 시도 횟수는 0부터 시작합니다.
     *
     * 같은 이메일로 다시 요청하면 이전 코드를 덮어씁니다.
     * 여러 개를 살려 두면 어느 것이든 맞히면 되어 맞힐 확률이 올라갑니다.
     */
    void saveCode(String email, String code);

    /**
     * 저장된 코드를 가져옵니다. 없으면 만료됐거나 요청한 적이 없는 것입니다.
     */
    Optional<String> findCode(String email);

    /**
     * 틀린 횟수를 하나 올리고 그 값을 돌려줍니다.
     *
     * 6자리는 백만 가지뿐이라 무차별 대입이 실제로 가능하므로 횟수를 셉니다.
     */
    int increaseAttempt(String email);

    /**
     * 코드를 지웁니다. 맞혔을 때와 시도 횟수를 넘겼을 때 부릅니다.
     */
    void deleteCode(String email);

    /**
     * 인증을 통과했다는 표시를 남깁니다.
     */
    void markVerified(String email);

    /**
     * 이 이메일이 인증을 통과한 상태인지 봅니다.
     */
    boolean isVerified(String email);

    /**
     * 인증 표시를 지웁니다. 가입에 성공하면 부릅니다.
     *
     * 남겨 두면 같은 표시로 여러 번 가입을 시도할 수 있습니다.
     * 표시의 뜻이 "이번 가입에 쓸 수 있다" 이므로 쓴 뒤에는 없애는 것이 맞습니다.
     */
    void clearVerified(String email);
}
