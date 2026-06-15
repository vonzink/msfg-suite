package com.msfg.los.disclosures.web.dto;

import java.util.List;

/**
 * Result of the TRID coverage gate (12 CFR 1026.19).
 *
 * @param covered true when the loan is subject to the TRID LE/CD disclosure regime
 * @param reasons human-readable rationale for the decision (always non-empty)
 */
public record CoverageResponse(boolean covered, List<String> reasons) {}
