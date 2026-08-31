package com.pawtrail.auth.domain.repository;

import java.util.Optional;

/**
 * 비밀번호 재설정 코드를 보관하는 약속입니다.
 *
 * 담는 것이 가입 인증과 거의 같습니다. 6자리 코드, 만료, 시도 횟수.
 * 그런데도 인터페이스를 나눈 이유는 성공했을 때 하는 일이 다르기 때문입니다.
 *
 *   가입 인증   맞히면 통과 표시를 남기고, 나중에 가입이 그것을 확인함
 *   재설정      맞히는 것과 비밀번호를 바꾸는 것이 한 요청이라 남길 표시가 없음
 *
 * 구조가 같다는 것과 같은 것이라는 뜻은 다릅니다.
 * 지금 합치면 표시를 남기는 쪽에만 필요한 메서드가 양쪽에 생기고,
 * 나중에 어느 한쪽이 달라질 때 다시 갈라야 합니다.
 *
 * 코드를 만들고 대조하고 횟수를 세는 로직은 VerificationCodeGenerator 가
 * 공유하므로 중복되는 것은 이 인터페이스의 메서드 이름뿐입니다.
 */
public interface PasswordResetStore {

    void saveCode(String email, String code);

    Optional<String> findCode(String email);

    int increaseAttempt(String email);

    void deleteCode(String email);
}
