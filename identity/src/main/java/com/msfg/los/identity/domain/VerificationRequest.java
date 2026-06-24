package com.msfg.los.identity.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import com.msfg.los.platform.notify.VerificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One staff-initiated borrower verification attempt (security spec §6.2). The row IS both the OTP store
 * and the audit record: it holds ONLY a salted one-way hash of the 6-digit code (never the plaintext,
 * never reversible ciphertext), a TTL, an attempts counter, and a single-use {@code consumedAt} marker.
 *
 * <p>{@code orgId} (via {@code @TenantId}), {@code createdBy} (the acting staff {@code sub}), and the
 * audit timestamps come from {@link TenantScopedEntity}/its parents — never set them by hand.
 */
@Entity
@Table(name = "verification_request")
@Getter
@Setter
public class VerificationRequest extends TenantScopedEntity {

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    @Column(name = "borrower_id", nullable = false)
    private UUID borrowerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationChannel channel;

    @Column(name = "code_hash", nullable = false, length = 120)
    private String codeHash;

    @Column(name = "code_salt", nullable = false, length = 40)
    private String codeSalt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "consumed_at")
    private Instant consumedAt;
}
