package com.pawtrail.auth.presentation.request.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

/**
 * MaxBytes 를 실제로 검사합니다.
 */
public class MaxBytesValidator implements ConstraintValidator<MaxBytes, String> {

    private int maxBytes;

    @Override
    public void initialize(MaxBytes constraint) {
        this.maxBytes = constraint.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {

        // 비어 있는 것은 여기서 판단하지 않음
        //
        // 그 검사는 NotBlank 의 몫이고, 여기서도 막으면
        // 값이 없을 때 오류 메시지가 두 개 나가 사용자가 무엇을 고쳐야 할지 흐려집니다.
        if (value == null) {
            return true;
        }

        // 문자열이 아니라 바이트로 세는 것이 이 검증의 전부임
        // 인코딩을 지정하는 것이 중요한데, 지정하지 않으면 실행 환경의 기본값을 따라
        // 개발 기기와 서버에서 결과가 갈릴 수 있음
        return value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }
}
