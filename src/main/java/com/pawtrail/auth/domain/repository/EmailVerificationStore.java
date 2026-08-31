package com.pawtrail.auth.domain.repository;

/**
 * 회원가입 이메일 인증 상태를 보관하는 약속입니다.
 *
 * 지금은 확인과 삭제만 있습니다.
 * 코드를 만들어 보내고 대조하는 부분은 이메일 인증 이슈에서 이 인터페이스에 추가됩니다.
 * 그때까지는 가입을 시험하려면 검증 통과 표시를 직접 넣어야 합니다.
 *
 *   docker compose exec redis redis-cli SET "emailverified:test@example.com" 1 EX 1800
 *
 * 검사를 그 이슈로 미루지 않고 지금 넣는 이유는, 나중에 넣으면 빠뜨리기 때문입니다.
 * 가입이 잘 되는 것을 보고 넘어가면 인증 없이 가입되는 상태로 남습니다.
 *
 * 표시를 따로 두는 이유
 *
 * 인증과 가입이 별개 요청이라 "이 이메일은 방금 확인됐다" 를 어딘가 남기지 않으면
 * 인증을 건너뛰고 가입만 직접 부를 수 있습니다.
 * 코드를 맞힌 사람과 가입하는 사람이 같다는 것을 잇는 것이 이 표시입니다.
 */
public interface EmailVerificationStore {

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
