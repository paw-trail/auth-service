package com.pawtrail.auth.presentation.request.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 문자열의 UTF-8 바이트 길이를 제한합니다.
 *
 * 왜 필요한가
 *
 * Size 는 글자 수만 셉니다.
 * 그런데 비밀번호를 해싱하는 BCrypt 는 72바이트를 넘는 입력을 거부합니다.
 * 한글은 한 글자가 3바이트라 25자만 넘어도 그 한계를 넘습니다.
 *
 *   Size(max = 64) 통과   →   BCrypt 가 encode 에서 IllegalArgumentException
 *
 * 그러면 형식 검증을 통과한 요청이 서비스에서 터져 500 이 나가고,
 * 사용자는 비밀번호가 왜 거부됐는지 알 수 없습니다.
 * 검증은 형식을 보는 층에서 끝나야 합니다.
 *
 * 왜 글자 수를 줄이지 않는가
 *
 * Size(max = 24) 로 두면 한글도 안전하지만 영문을 쓰는 사람이 손해를 봅니다.
 * 비밀번호는 길수록 안전한데 그것을 막을 이유가 없습니다.
 */
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxBytesValidator.class)
public @interface MaxBytes {

    /**
     * 허용할 최대 바이트 수입니다.
     */
    int value();

    String message() default "입력이 너무 깁니다";

    /**
     * 아래 둘은 Bean Validation 규격이 요구하는 항목입니다.
     * 우리가 쓰지는 않지만 없으면 애노테이션으로 인정되지 않습니다.
     */
    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
