package com.msfg.los.income.web.dto;

import com.msfg.los.income.domain.EmploymentClassificationType;
import com.msfg.los.income.domain.EmploymentStatusType;
import com.msfg.los.income.domain.OwnershipInterestType;
import com.msfg.los.platform.reference.UsStateCode;

import java.time.LocalDate;

public record AddEmploymentRequest(
        String employerName,
        String employerPhone,
        String employerAddressLine1,
        String employerAddressLine2,
        String employerCity,
        UsStateCode employerState,
        String employerPostalCode,
        String positionTitle,
        EmploymentStatusType employmentStatus,
        EmploymentClassificationType classification,
        Boolean selfEmployed,
        OwnershipInterestType ownershipShare,
        Boolean employedByPartyToTransaction,
        LocalDate startDate,
        LocalDate endDate,
        Integer monthsInLineOfWork) {}
