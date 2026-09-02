package com.pawtrail.auth.presentation.request;

import com.pawtrail.auth.application.dto.input.EmailVerifyInput;
import com.pawtrail.auth.application.dto.input.PasswordResetInput;
import com.pawtrail.auth.presentation.request.validation.MaxBytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 인증 코드 관련 요청 네 가지입니다.
 *
 * 한 파일에 모으는 것은 셋이 같은 흐름에 속하고 필드도 겹치기 때문입니다.
 * 흩어 두면 이메일 형식 규칙이 여러 파일에 복사됩니다.
 */
public final class EmailVerificationRequest {

    private EmailVerificationRequest() {
    }

    /**
     * 코드를 보내 달라는 요청입니다. 가입 인증과 비밀번호 재설정이 같은 모양을 씁니다.
     */
    public record SendCode(

            @NotBlank(message = "이메일을 입력해 주세요")
            @Email(message = "이메일 형식이 올바르지 않습니다")
            @Size(max = 255, message = "이메일은 255자를 넘을 수 없습니다")
            String email
    ) {
    }

    /**
     * 코드를 확인해 달라는 요청입니다. 회원가입 인증에서 씁니다.
     */
    public record VerifyCode(

            @NotBlank(message = "이메일을 입력해 주세요")
            @Email(message = "이메일 형식이 올바르지 않습니다")
            String email,

            // 여섯 자리 숫자만 받음
            //
            // 형식이 틀린 것을 여기서 걸러도 계정 정보가 새지 않음
            // 어떤 이메일이 가입돼 있는지와 무관한 검사이기 때문임
            @NotBlank(message = "인증 코드를 입력해 주세요")
            @Pattern(regexp = "\\d{6}", message = "인증 코드는 6자리 숫자입니다")
            String code
    ) {

        public EmailVerifyInput toInput() {
            return new EmailVerifyInput(email, code);
        }
    }

    /**
     * 탈퇴하겠다는 요청입니다.
     *
     * 이메일을 받지 않습니다.
     * 로그인한 사람만 부를 수 있고 그 사람의 주소는 계정에 있으므로,
     * 받으면 남의 주소를 넣을 수 있는 자리만 하나 생깁니다.
     */
    public record Withdraw(

            @NotBlank(message = "인증 코드를 입력해 주세요")
            @Pattern(regexp = "\\d{6}", message = "인증 코드는 6자리 숫자입니다")
            String code
    ) {
    }

    /**
     * 비밀번호를 바꿔 달라는 요청입니다.
     *
     * 코드 확인과 변경이 한 요청인 것은, 나누면 "코드를 맞혔다" 를 어딘가 남겨야 하고
     * 그 표시가 새어 나가면 코드를 모르는 사람이 비밀번호를 바꿀 수 있기 때문입니다.
     */
    public record ResetPassword(

            @NotBlank(message = "이메일을 입력해 주세요")
            @Email(message = "이메일 형식이 올바르지 않습니다")
            String email,

            @NotBlank(message = "인증 코드를 입력해 주세요")
            @Pattern(regexp = "\\d{6}", message = "인증 코드는 6자리 숫자입니다")
            String code,

            // 가입 때와 같은 규칙임. 근거는 SignupRequest 에 적어 두었음
            @NotBlank(message = "새 비밀번호를 입력해 주세요")
            @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하여야 합니다")
            @MaxBytes(value = 72, message = "비밀번호가 너무 깁니다. 한글은 한 글자가 세 자리로 계산됩니다")
            String newPassword
    ) {

        public PasswordResetInput toInput() {
            return new PasswordResetInput(email, code, newPassword);
        }
    }
}
