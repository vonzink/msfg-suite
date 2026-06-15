package com.msfg.los.disclosures.service;

import java.util.UUID;

/**
 * Seam to a disclosure document/delivery vendor.
 *
 * <p>Real adapters = DocMagic/IDS/Docutech "generate disclosure package" (MISMO 3.4 loan data in;
 * computed APR/finance-charge + regulated H-24/H-25 PDF out; the CREDITOR remains liable for
 * accuracy). send() requires ESIGN 15 USC 7001(c) consent evidenced first. getStatus() returns the
 * e-view/e-sign timestamp that flips receipt basis to ACTUAL. exportUcd() = MISMO v3.3.0 Uniform
 * Closing Dataset for GSE delivery — deferred, stub only.
 */
public interface DisclosureVendorPort {

    DisclosureGenerationResult generate(DisclosureGenerationRequest request);

    DeliveryResult send(DeliveryRequest request);

    DeliveryStatus getStatus(String vendorReference);

    UcdExportResult exportUcd(UUID loanId, UUID disclosureId);
}
