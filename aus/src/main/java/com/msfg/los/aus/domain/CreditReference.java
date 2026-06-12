package com.msfg.los.aus.domain;

import java.util.UUID;

/** Per-borrower credit reference number (used on AUS reissue). jsonb payload inside AusVendorSettings. */
public record CreditReference(UUID borrowerId, String reference) {}
