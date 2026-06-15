package com.msfg.los.disclosures.web.dto;

import java.time.LocalDate;

/**
 * Loan-level TRID timing rollup: the latest-version earliest-consummation dates per kind, the
 * overall binding earliest, and whether the loan's set consummation date satisfies it.
 * {@code revisedLeDeadline} is null in this task; Task 11 wires the CoC revised-LE clock.
 */
public record TimingResponse(
        LocalDate latestLeEarliestConsummation,
        LocalDate latestCdEarliestConsummation,
        LocalDate overallEarliestConsummation,
        LocalDate consummationDate,
        Boolean consummationSatisfiesTiming,
        LocalDate revisedLeDeadline) {}
