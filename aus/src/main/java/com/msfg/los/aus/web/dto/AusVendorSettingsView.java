package com.msfg.los.aus.web.dto;

import com.msfg.los.aus.domain.AusIssueMode;
import com.msfg.los.aus.domain.CredentialSource;
import com.msfg.los.aus.domain.CreditReference;

import java.util.List;

/**
 * One vendor's slice of the AUS profile: the stored submission settings plus where this loan's
 * credentials would come from right now (LOAN override / ORG default / NONE).
 * {@code creditReferences} is never null — empty list when unset.
 */
public record AusVendorSettingsView(AusIssueMode issueMode, String creditProviderCode, String fhaCaseNumber,
        String branchNumber, List<CreditReference> creditReferences, CredentialSource credentialSource) {}
