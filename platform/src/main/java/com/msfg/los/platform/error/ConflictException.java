package com.msfg.los.platform.error;
import org.springframework.http.HttpStatus;
public class ConflictException extends DomainException {
    public ConflictException(String message) { super(HttpStatus.CONFLICT, "CONFLICT", message); }
}
