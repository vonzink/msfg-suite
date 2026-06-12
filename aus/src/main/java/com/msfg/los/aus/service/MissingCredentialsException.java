package com.msfg.los.aus.service;

import com.msfg.los.aus.domain.AusVendor;
import com.msfg.los.platform.error.DomainException;
import org.springframework.http.HttpStatus;

/** 409: the loan's org has no credentials for the AUS vendor (and no loan override). */
public class MissingCredentialsException extends DomainException {
    public MissingCredentialsException(AusVendor vendor) {
        super(HttpStatus.CONFLICT, "MISSING_CREDENTIALS",
                "No %s credentials configured (org or loan)".formatted(vendor));
    }
}
