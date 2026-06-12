package com.msfg.los.pricing.service;

import com.msfg.los.platform.error.DomainException;
import org.springframework.http.HttpStatus;

public class LockStateConflictException extends DomainException {
    public LockStateConflictException(String message) {
        super(HttpStatus.CONFLICT, "LOCK_STATE_CONFLICT", message);
    }
}
