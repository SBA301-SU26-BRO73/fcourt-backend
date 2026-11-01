package com.fcourt.auth.account;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * Limits a string by its UTF-8 size. BCrypt hashes at most 72 bytes and Spring's encoder throws for
 * longer input, so a 72 character password with Vietnamese diacritics (2 to 3 bytes each) would
 * otherwise pass {@code @Size} and fail with a 500. {@code null} is left to {@code @NotBlank}.
 */
@Documented
@Constraint(validatedBy = MaxUtf8Bytes.Validator.class)
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface MaxUtf8Bytes {
    int value();

    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<MaxUtf8Bytes, String> {
        private int max;

        @Override
        public void initialize(MaxUtf8Bytes constraint) {
            this.max = constraint.value();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
        }
    }
}
