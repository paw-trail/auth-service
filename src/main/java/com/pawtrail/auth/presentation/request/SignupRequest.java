package com.pawtrail.auth.presentation.request;

import com.pawtrail.auth.application.dto.input.SignupInput;
import com.pawtrail.auth.presentation.request.validation.MaxBytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청입니다.
 *
 * 여기서 걸리는 것은 형식뿐입니다.
 * "이미 쓰는 이메일인가", "이메일 인증을 거쳤는가" 는 서비스가 판단합니다.
 * 실패하면 공통 모듈이 VALIDATION_FAILED 와 함께 어느 필드가 왜 틀렸는지를 담아 보냅니다.
 *
 * @param email    로그인 아이디이자 계정 복구의 유일한 수단입니다.
 * @param password 평문입니다. 서비스가 BCrypt 로 해싱한 뒤 저장하며 로그에 남기지 않습니다.
 * @param nickname 이 서비스는 저장하지 않고 account.created 이벤트로 user 에게 넘깁니다.
 */
public record SignupRequest(

        @NotBlank(message = "이메일을 입력해 주세요")
        @Email(message = "이메일 형식이 올바르지 않습니다")
        @Size(max = 255, message = "이메일은 255자를 넘을 수 없습니다")
        String email,

        // 길이만 제한하고 문자 조합은 강제하지 않음
        //
        // * 조합을 강제하면 오히려 예측하기 쉬운 값이 양산됩니다
        //   대문자와 특수문자를 요구하면 Password1! 같은 형태로 수렴함
        //   미국 표준 기관도 조합 요구를 권장하지 않는 쪽으로 바뀌었음
        //
        // * 상한을 글자 수와 바이트 수로 두 번 겁니다
        //   BCrypt 가 72바이트를 넘는 입력을 거부하는데 Size 는 글자 수만 세므로,
        //   한글 25자처럼 글자 수는 적고 바이트는 큰 값이 그대로 통과함
        //   그러면 형식 검증을 지난 요청이 해싱하는 자리에서 터져 500 이 나갑니다
        //   글자 수를 24로 줄이면 이 문제는 사라지지만 영문을 쓰는 사람이 손해를 봅니다
        @NotBlank(message = "비밀번호를 입력해 주세요")
        @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하여야 합니다")
        @MaxBytes(value = 72, message = "비밀번호가 너무 깁니다. 한글은 한 글자가 세 자리로 계산됩니다")
        String password,

        // 중복을 허용함
        //
        // 닉네임이 쓰이는 자리가 후기 작성자 표시 하나뿐이고
        // 그것으로 사람을 찾거나 부르는 기능이 없기 때문임
        // 중복을 막으려면 가입 시점에 검사해야 하는데,
        // 그러면 auth 가 user 를 호출하게 되어 이벤트로 넘기는 구조가 무너집니다.
        @NotBlank(message = "닉네임을 입력해 주세요")
        @Size(min = 2, max = 20, message = "닉네임은 2자 이상 20자 이하여야 합니다")
        String nickname
) {

    /**
     * 서비스가 받는 형태로 바꿉니다.
     *
     * 검증은 여기까지가 끝이고 그 아래는 값만 흐릅니다.
     */
    public SignupInput toInput() {
        return new SignupInput(email, password, nickname);
    }
}
