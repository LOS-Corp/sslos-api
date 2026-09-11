package com.laundry.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.laundry.auth.dto.request.RegisterRequest;

class RegisterRequestValidationTests {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void rejectsInvalidEmailPhoneAndWeakPassword() {
        RegisterRequest request = new RegisterRequest(
            "Customer", "invalid", "0123456789", "password", "password");

        Set<String> fields = validator.validate(request).stream()
            .map(error -> error.getPropertyPath().toString())
            .collect(java.util.stream.Collectors.toSet());

        assertThat(fields).contains("email", "phone", "password");
    }

    @Test
    void acceptsValidRegistrationFields() {
        RegisterRequest request = new RegisterRequest(
            "Customer", "customer@example.com", "+84901234567", "Strong!1", "Strong!1");

        assertThat(validator.validate(request)).isEmpty();
    }
}
