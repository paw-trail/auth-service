package com.pawtrail.auth.application.dto.input;

/**
 * 회원가입 입력입니다.
 *
 * 문자열 세 값은 순서를 바꿔 넣어도 오류가 나지 않습니다.
 * 이름으로 넣게 해 그 자리를 없앱니다.
 *
 * @param email    로그인 아이디이자 계정 복구의 유일한 수단입니다.
 * @param password 아직 해싱하지 않은 값입니다. 서비스가 저장 직전에 해싱합니다.
 * @param nickname account 에 저장하지 않고 account.created 이벤트로 넘깁니다.
 *                 user_profile 이 소유자입니다.
 * @param client   가입 직후 자동으로 로그인시키므로 발급 이력에 남길 접속 정보가 필요합니다.
 *                 로그인 입력과 같은 값입니다.
 */
public record SignupInput(String email, String password, String nickname, ClientInfo client) {
}
