package com.msfg.los.income.web.dto;

import com.msfg.los.income.domain.Employment;
import com.msfg.los.income.domain.EmploymentClassificationType;
import com.msfg.los.income.domain.EmploymentStatusType;
import com.msfg.los.income.domain.OwnershipInterestType;
import com.msfg.los.platform.reference.UsStateCode;

import java.time.LocalDate;
import java.util.UUID;

public record EmploymentResponse(
        UUID id,
        UUID borrowerId,
        int ordinal,
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
        Integer monthsInLineOfWork) {

    public static EmploymentResponse from(Employment e) {
        return new EmploymentResponse(
                e.getId(),
                e.getBorrowerId(),
                e.getOrdinal(),
                e.getEmployerName(),
                e.getEmployerPhone(),
                e.getEmployerAddressLine1(),
                e.getEmployerAddressLine2(),
                e.getEmployerCity(),
                e.getEmployerState(),
                e.getEmployerPostalCode(),
                e.getPositionTitle(),
                e.getEmploymentStatus(),
                e.getClassification(),
                e.getSelfEmployed(),
                e.getOwnershipShare(),
                e.getEmployedByPartyToTransaction(),
                e.getStartDate(),
                e.getEndDate(),
                e.getMonthsInLineOfWork());
    }
}
