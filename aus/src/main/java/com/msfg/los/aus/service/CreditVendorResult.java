package com.msfg.los.aus.service;

import com.msfg.los.aus.domain.CreditScoreEntry;

import java.util.List;

/** Result of one credit order: bureau reference, per-borrower-per-bureau scores, report artifact. */
public record CreditVendorResult(String creditReportIdentifier, List<CreditScoreEntry> scores, VendorArtifact report) {}
