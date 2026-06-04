package com.msfg.los.loan.domain;

import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Embeddable
@Getter
@Setter
public class SubjectProperty {
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;
    private BigDecimal estimatedValue;
}
