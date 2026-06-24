package com.msfg.los.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Body for {@code POST /api/identity/borrowers/{borrowerId}/verify-code} (security spec §6.2).
 * {@code loanId} scopes the access decision; {@code code} is the 6-digit OTP the borrower received.
 * Both required — a missing/blank value yields the flat 400 envelope. The code is NEVER echoed back.
 */
public record VerifyCodeRequest(
        @NotBlank String code,
        @jakarta.validation.constraints.NotNull UUID loanId) {
}
