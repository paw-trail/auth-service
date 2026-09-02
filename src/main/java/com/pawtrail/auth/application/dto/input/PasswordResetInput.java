package com.pawtrail.auth.application.dto.input;

/**
 * 비밀번호 재설정 입력입니다.
 *
 * 코드 확인과 변경이 한 요청인 것은, 나누면 "코드를 맞혔다" 를 어딘가 남겨야 하고
 * 그 표시가 새어 나가면 코드를 모르는 사람이 비밀번호를 바꿀 수 있기 때문입니다.
 *
 * @param email       코드를 받은 주소입니다.
 * @param code        사용자가 입력한 여섯 자리입니다.
 * @param newPassword 바꿀 값입니다. 아직 해싱하지 않았습니다.
 */
public record PasswordResetInput(String email, String code, String newPassword) {
}
