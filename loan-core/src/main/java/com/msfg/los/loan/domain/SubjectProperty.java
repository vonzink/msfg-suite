package com.msfg.los.loan.domain;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    // §4 Subject Property fields
    private BigDecimal salesPrice;
    private BigDecimal appraisedValue;

    @Enumerated(EnumType.STRING)
    private PropertyType propertyType;

    @Enumerated(EnumType.STRING)
    private OccupancyType occupancyType;

    private Integer numberOfUnits;
}
