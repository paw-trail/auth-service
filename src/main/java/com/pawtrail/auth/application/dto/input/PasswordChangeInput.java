package com.pawtrail.auth.application.dto.input;

/**
 * 비밀번호 변경 입력입니다.
 *
 * 계정 식별자는 담지 않습니다.
 * 그 값은 요청 바디가 아니라 게이트웨이가 넣어 준 헤더에서 오므로,
 * 사용자가 보낸 것과 우리가 확인한 것을 한 상자에 섞지 않습니다.
 *
 * 두 값이 같은 타입이라 뒤집어 넣으면 현재 비밀번호 확인이 실패합니다.
 * 오류가 나기는 하지만 원인이 "비밀번호가 틀렸다" 로 나와 찾기 어렵습니다.
 *
 * @param currentPassword 본인 확인용입니다.
 * @param newPassword     바꿀 값입니다.
 */
public record PasswordChangeInput(String currentPassword, String newPassword) {
}
