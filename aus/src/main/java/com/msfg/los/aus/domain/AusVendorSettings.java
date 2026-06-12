package com.msfg.los.aus.domain;

import java.util.List;

/** Per-vendor AUS submission settings. Stored as a jsonb column on AusProfile (du_settings / lpa_settings). */
public record AusVendorSettings(AusIssueMode issueMode, String creditProviderCode, String fhaCaseNumber,
        String branchNumber, List<CreditReference> creditReferences) {}
