package com.msfg.los.platform.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Salted one-way hashing for short-lived one-time passcodes (OTP) — the at-rest protection for
 * {@code verification_request.code_hash} (security spec §6.2).
 *
 * <p>A 6-digit OTP is low-entropy, but it is single-use, TTL-bounded (5–10 min), attempt-capped (≤5),
 * and rate-limited at the send side, so a per-row random salt + a single SHA-256 round is sufficient
 * and avoids needing a tunable KDF. Codes are stored ONLY as {@link #hash(String, String)} output —
 * never via the reversible {@code EncryptedStringConverter}. Verification uses a constant-time compare
 * ({@link MessageDigest#isEqual}) to avoid leaking via timing.
 *
 * <p>Stateless utility — no key material, so no Spring bean needed (unlike {@code NpiCipher}).
 */
public final class OtpHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT_BYTES = 16;

    private OtpHasher() {
    }

    /** A fresh random 16-byte salt, Base64-encoded for column storage. */
    public static String newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /**
     * SHA-256 of {@code salt || code}, Base64-encoded. Deterministic for a given (code, salt) pair so
     * the stored hash can be recomputed and compared on verify.
     */
    public static String hash(String code, String saltBase64) {
        if (code == null || saltBase64 == null) {
            throw new IllegalArgumentException("code and salt are required");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Base64.getDecoder().decode(saltBase64));
            byte[] digest = md.digest(code.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("OTP hash failed", e);
        }
    }

    /**
     * Constant-time check that {@code candidate} hashes (under {@code saltBase64}) to
     * {@code storedHashBase64}. Returns {@code false} (never throws) on any null input.
     */
    public static boolean matches(String candidate, String saltBase64, String storedHashBase64) {
        if (candidate == null || saltBase64 == null || storedHashBase64 == null) {
            return false;
        }
        byte[] expected = Base64.getDecoder().decode(storedHashBase64);
        byte[] actual = Base64.getDecoder().decode(hash(candidate, saltBase64));
        return MessageDigest.isEqual(expected, actual);
    }
}
