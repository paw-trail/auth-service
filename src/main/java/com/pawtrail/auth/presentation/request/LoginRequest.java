package com.pawtrail.auth.presentation.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청입니다.
 *
 * 가입 요청과 달리 형식 검증을 거의 하지 않습니다.
 *
 * 이메일 형식이나 비밀번호 길이를 여기서 막으면
 * "형식이 틀렸다" 와 "비밀번호가 틀렸다" 가 다른 응답으로 나가고,
 * 그 차이로 어떤 이메일이 가입되어 있는지를 짐작할 수 있게 됩니다.
 * 비어 있는지만 보고 나머지는 전부 같은 실패로 처리합니다.
 */
public record LoginRequest(

        @NotBlank(message = "이메일을 입력해 주세요")
        String email,

        @NotBlank(message = "비밀번호를 입력해 주세요")
        String password
) {
}
