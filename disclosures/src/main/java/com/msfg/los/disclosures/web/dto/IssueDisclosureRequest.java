package com.msfg.los.disclosures.web.dto;

import com.msfg.los.disclosures.domain.DeliveryMethod;

import java.util.UUID;

/**
 * Request body for issuing a disclosure. Both fields are optional: {@code deliveryMethod} defaults
 * to EMAIL when absent; {@code triggerCocId} links a revised disclosure to the Change-of-Circumstance
 * that prompted it (used by the CD/reset flow in Task 10).
 */
public record IssueDisclosureRequest(DeliveryMethod deliveryMethod, UUID triggerCocId) {}
