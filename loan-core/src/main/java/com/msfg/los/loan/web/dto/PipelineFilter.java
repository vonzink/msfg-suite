package com.msfg.los.loan.web.dto;

import com.msfg.los.loan.domain.LoanStatus;
import com.msfg.los.loan.domain.MortgageType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bound query parameters for the pipeline list (Phase 2 T4). Every field is optional; nulls/empties
 * mean "no constraint for this facet". The service translates non-null facets into
 * {@code LoanSpecifications} predicates (query-side).
 *
 * @param statuses    {@code status} — status in (...). One element preserves the legacy single-status behavior.
 * @param lo          {@code lo} — assigned loan officer id.
 * @param conditionsGt {@code conditionsGt} — keep loans with > N outstanding conditions (cross-module via conditions service).
 * @param closingFrom {@code closingFrom} — consummationDate >= this.
 * @param closingTo   {@code closingTo} — consummationDate <= this.
 * @param stageAgeGt  {@code stageAgeGt} — days; statusChangedAt older than now - days.
 * @param loanTypes   {@code loanType} — mortgageType in (...).
 * @param amountMin   {@code amountMin} — primary loan amount >= this.
 * @param amountMax   {@code amountMax} — primary loan amount <= this.
 */
public record PipelineFilter(
        List<LoanStatus> statuses,
        UUID lo,
        Integer conditionsGt,
        LocalDate closingFrom,
        LocalDate closingTo,
        Integer stageAgeGt,
        List<MortgageType> loanTypes,
        BigDecimal amountMin,
        BigDecimal amountMax) {
}
