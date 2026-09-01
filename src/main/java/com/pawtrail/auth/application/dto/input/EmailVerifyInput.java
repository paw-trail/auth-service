package com.pawtrail.auth.application.dto.input;

/**
 * 회원가입 이메일 인증 확인 입력입니다.
 *
 * @param email 코드를 받은 주소입니다.
 * @param code  사용자가 입력한 여섯 자리입니다.
 */
public record EmailVerifyInput(String email, String code) {
}
