package com.msfg.los.pricing.web.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.*;
import java.util.Set;

@Documented
@Constraint(validatedBy = AllowedCommitmentDays.Validator.class)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowedCommitmentDays {
    String message() default "must be one of 15, 30, 45, 60, 90";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<AllowedCommitmentDays, Integer> {
        private static final Set<Integer> ALLOWED = Set.of(15, 30, 45, 60, 90);
        @Override
        public boolean isValid(Integer value, ConstraintValidatorContext ctx) {
            return value == null || ALLOWED.contains(value);   // null is @NotNull's job
        }
    }
}
