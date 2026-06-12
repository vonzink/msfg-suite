package com.msfg.los.aus.service;

import com.msfg.los.aus.domain.AusRecommendation;

import java.util.List;

/** Normalized result of one AUS submission (raw vendor strings kept alongside the enum). */
public record AusVendorResult(String vendorCaseId, String vendorTransactionId, AusRecommendation recommendation,
        String rawRecommendation, String rawEligibility, List<String> messages, List<VendorArtifact> artifacts) {}
