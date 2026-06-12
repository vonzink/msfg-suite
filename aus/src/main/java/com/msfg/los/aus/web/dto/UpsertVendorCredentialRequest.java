package com.msfg.los.aus.web.dto;

/**
 * Replace-only upsert payload for a vendor credential.
 * Identity fields: null = keep current value, non-null = overwrite.
 * Secret fields (username/password/creditUsername/creditPassword):
 * null = keep, "" (blank) = clear, non-blank = set.
 */
public record UpsertVendorCredentialRequest(String institutionId, String sellerServicerNumber, String tpoNumber,
        String branchNumber, String creditProviderCode, String creditAffiliateCode,
        String username, String password, String creditUsername, String creditPassword) {}
