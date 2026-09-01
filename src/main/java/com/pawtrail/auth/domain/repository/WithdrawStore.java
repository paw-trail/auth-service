package com.pawtrail.auth.domain.repository;

import java.util.Optional;

/**
 * 탈퇴 인증 코드를 보관하는 약속입니다.
 *
 * 담는 것이 비밀번호 재설정과 같습니다. 6자리 코드, 만료, 시도 횟수.
 * 그런데도 저장소를 나누는 이유는 두 코드가 서로 통용되면 안 되기 때문입니다.
 *
 * 재설정 코드를 보내는 경로는 인증 없이 열려 있습니다.
 * 비밀번호를 잊은 사람이 부르는 것이라 로그인을 요구할 수 없습니다.
 * 그래서 남의 이메일을 넣어도 그 주소로 코드가 갑니다.
 *
 * 저장 자리를 공유하면 그렇게 받아 낸 코드로 탈퇴까지 됩니다.
 * 코드를 손에 넣은 사람이 할 수 있는 일이 "비밀번호 바꾸기" 에서
 * "계정 지우기" 로 넓어지는 것이라 접두사를 갈라 둡니다.
 *
 * 코드를 만들고 대조하고 횟수를 세는 로직은 VerificationCodeGenerator 가
 * 공유하므로 중복되는 것은 이 인터페이스의 메서드 이름뿐입니다.
 */
public interface WithdrawStore {

    void saveCode(String email, String code);

    Optional<String> findCode(String email);

    int increaseAttempt(String email);

    void deleteCode(String email);
}
