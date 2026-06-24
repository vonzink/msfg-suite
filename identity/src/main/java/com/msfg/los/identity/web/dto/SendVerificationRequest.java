package com.msfg.los.identity.web.dto;

import com.msfg.los.platform.notify.VerificationChannel;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body for {@code POST /api/identity/borrowers/{borrowerId}/send-verification} (security spec §6.2).
 * {@code loanId} scopes the access decision; {@code channel} picks the delivery rail. Both required —
 * a missing/unknown value yields the flat {@code {success,code,message,fields,timestamp}} 400 envelope.
 */
public record SendVerificationRequest(
        @NotNull VerificationChannel channel,
        @NotNull UUID loanId) {
}
