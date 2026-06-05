package com.msfg.los.platform.error;
import org.springframework.http.HttpStatus;
public class ValidationException extends DomainException {
    public ValidationException(String message) { super(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
}
