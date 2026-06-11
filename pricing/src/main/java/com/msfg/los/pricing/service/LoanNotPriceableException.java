package com.msfg.los.pricing.service;

import com.msfg.los.platform.error.DomainException;
import org.springframework.http.HttpStatus;

public class LoanNotPriceableException extends DomainException {
    public LoanNotPriceableException(String message) {
        super(HttpStatus.CONFLICT, "LOAN_NOT_PRICEABLE", message);
    }
}
