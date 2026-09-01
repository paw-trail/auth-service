package com.pawtrail.auth.application.dto.input;

/**
 * 이메일 로그인 입력입니다.
 *
 * @param email    로그인 아이디입니다.
 * @param password 아직 해싱하지 않은 값입니다.
 * @param client   발급 이력에 남길 접속 정보입니다.
 */
public record LoginInput(String email, String password, ClientInfo client) {
}
