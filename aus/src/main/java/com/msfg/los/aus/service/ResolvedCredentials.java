package com.msfg.los.aus.service;

import com.msfg.los.aus.domain.CredentialSource;

/** Vendor credentials after org/loan resolution — plaintext, in-memory only (never persisted as-is). */
public record ResolvedCredentials(CredentialSource source, String institutionId, String sellerServicerNumber,
        String tpoNumber, String branchNumber, String username, String password,
        String creditProviderCode, String creditAffiliateCode, String creditUsername, String creditPassword) {}
