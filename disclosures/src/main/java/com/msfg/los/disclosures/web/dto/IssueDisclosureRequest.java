package com.msfg.los.disclosures.web.dto;

import com.msfg.los.disclosures.domain.DeliveryMethod;

import java.util.UUID;

/**
 * Request body for issuing a disclosure. All fields are optional: {@code deliveryMethod} defaults
 * to EMAIL when absent; {@code triggerCocId} links a revised disclosure to the Change-of-Circumstance
 * that prompted it (used by the CD/reset flow); {@code prepaymentPenalty} overrides the assembled
 * prepayment-penalty flag (no loan field carries it yet) so a CD re-disclosure can reflect a newly
 * added prepayment penalty and drive the {@code PREPAYMENT_PENALTY_ADDED} reset trigger.
 */
public record IssueDisclosureRequest(DeliveryMethod deliveryMethod, UUID triggerCocId, Boolean prepaymentPenalty) {}
