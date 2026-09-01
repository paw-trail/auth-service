package com.pawtrail.auth.presentation.request;

import com.pawtrail.auth.presentation.request.validation.MaxBytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 변경 요청입니다.
 *
 * 현재 비밀번호를 함께 받는 것이 이 기능과 재설정을 가르는 지점입니다.
 * 재설정은 비밀번호를 잊은 사람이 메일로 본인을 증명하지만,
 * 여기는 로그인한 사람이 현재 비밀번호로 증명합니다.
 *
 * 토큰만으로 바꿀 수 있게 두면 액세스 토큰을 주운 사람이 비밀번호를 바꿀 수 있습니다.
 * 그 토큰은 모든 요청에 실려 나가 노출 면적이 넓습니다.
 *
 * @param currentPassword 지금 쓰는 비밀번호입니다. 평문이며 서비스가 해시와 대조합니다.
 * @param newPassword     새로 쓸 비밀번호입니다.
 */
public record PasswordChangeRequest(

        @NotBlank(message = "현재 비밀번호를 입력해 주세요")
        String currentPassword,

        // 새 비밀번호에만 길이 규칙을 붙입니다.
        //
        // 현재 비밀번호는 이미 저장된 값과 대조만 하므로 형식을 볼 이유가 없습니다.
        // 규칙이 바뀌기 전에 만든 비밀번호를 쓰는 사람이 여기서 막히면
        // 비밀번호를 바꾸려는데 옛 비밀번호가 형식에 맞지 않아 못 바꾸는 상태가 됩니다.
        //
        // @Size 와 @MaxBytes 를 함께 붙이는 이유는 세는 단위가 다르기 때문입니다.
        // @Size 는 글자 수를 세는데 BCrypt 는 72바이트를 넘는 입력을 거부하고,
        // 한글은 한 글자가 세 자리라 25자만 넘어도 걸립니다.
        // 바이트 검증이 없으면 형식 검증을 통과한 요청이 해싱에서 터져 500 이 나갑니다.
        @NotBlank(message = "새 비밀번호를 입력해 주세요")
        @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하여야 합니다")
        @MaxBytes(value = 72, message = "비밀번호가 너무 깁니다. 한글은 한 글자가 세 자리로 계산됩니다")
        String newPassword
) {
}
