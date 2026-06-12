package com.msfg.los.aus.web.dto;

import com.msfg.los.aus.domain.CredentialVendor;

/**
 * Write-only credential view — THE CARDINAL RULE: there is deliberately NO field that could
 * carry a raw secret. Passwords surface as booleans only; usernames as boolean + mask.
 * Never add passwordMasked/raw fields here.
 */
public record VendorCredentialResponse(CredentialVendor vendor, String institutionId, String sellerServicerNumber,
        String tpoNumber, String branchNumber, String creditProviderCode, String creditAffiliateCode,
        boolean usernameSet, String usernameMasked, boolean passwordSet,
        boolean creditUsernameSet, String creditUsernameMasked, boolean creditPasswordSet) {}
