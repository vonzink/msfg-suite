package com.msfg.los.disclosures.web.dto;

import java.time.LocalDate;

/**
 * Loan-level TRID timing rollup: the latest-version earliest-consummation dates per kind, the
 * overall binding earliest, and whether the loan's set consummation date satisfies it.
 * {@code revisedLeDeadline} is the 3 general-business-day deadline to deliver a revised LE after the
 * most recent accepted Change-of-Circumstance (1026.19(e)(3)(iv)/(e)(4)), or null if none accepted.
 *
 * <p>{@code leDeliveryDeadline} is the 3 general-business-day initial-LE delivery deadline of
 * 1026.19(e)(1)(iii), measured from the application date. {@code leDeliveredOnTime} is whether the
 * latest LE was delivered on or before that deadline (null when no LE has been issued). The
 * application date is proxied by the loan's createdAt (UTC) in v1.
 */
public record TimingResponse(
        LocalDate latestLeEarliestConsummation,
        LocalDate latestCdEarliestConsummation,
        LocalDate overallEarliestConsummation,
        LocalDate consummationDate,
        Boolean consummationSatisfiesTiming,
        LocalDate revisedLeDeadline,
        LocalDate leDeliveryDeadline,
        Boolean leDeliveredOnTime) {}
