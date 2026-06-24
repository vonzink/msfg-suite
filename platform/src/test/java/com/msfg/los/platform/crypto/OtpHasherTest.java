package com.msfg.los.platform.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OtpHasher} — salted one-way SHA-256 with constant-time compare. This is the
 * OTP at-rest protection for {@code verification_request.code_hash} (security spec §6.2): codes are
 * stored ONLY as a salted hash, never reversible ciphertext, and verified with {@code MessageDigest.isEqual}.
 */
class OtpHasherTest {

    @Test
    void newSaltIs16BytesBase64() {
        String salt = OtpHasher.newSalt();
        assertThat(salt).isNotBlank();
        assertThat(java.util.Base64.getDecoder().decode(salt)).hasSize(16);
    }

    @Test
    void newSaltIsRandomPerCall() {
        assertThat(OtpHasher.newSalt()).isNotEqualTo(OtpHasher.newSalt());
    }

    @Test
    void hashIsDeterministicForSameCodeAndSalt() {
        String salt = OtpHasher.newSalt();
        assertThat(OtpHasher.hash("123456", salt)).isEqualTo(OtpHasher.hash("123456", salt));
    }

    @Test
    void hashDiffersForDifferentSalt() {
        assertThat(OtpHasher.hash("123456", OtpHasher.newSalt()))
                .isNotEqualTo(OtpHasher.hash("123456", OtpHasher.newSalt()));
    }

    @Test
    void hashDiffersForDifferentCode() {
        String salt = OtpHasher.newSalt();
        assertThat(OtpHasher.hash("123456", salt)).isNotEqualTo(OtpHasher.hash("654321", salt));
    }

    @Test
    void hashNeverEqualsPlaintext() {
        String salt = OtpHasher.newSalt();
        assertThat(OtpHasher.hash("123456", salt)).doesNotContain("123456");
    }

    @Test
    void matchesAcceptsCorrectCode() {
        String salt = OtpHasher.newSalt();
        String stored = OtpHasher.hash("424242", salt);
        assertThat(OtpHasher.matches("424242", salt, stored)).isTrue();
    }

    @Test
    void matchesRejectsWrongCode() {
        String salt = OtpHasher.newSalt();
        String stored = OtpHasher.hash("424242", salt);
        assertThat(OtpHasher.matches("000000", salt, stored)).isFalse();
    }

    @Test
    void matchesRejectsNulls() {
        String salt = OtpHasher.newSalt();
        String stored = OtpHasher.hash("424242", salt);
        assertThat(OtpHasher.matches(null, salt, stored)).isFalse();
        assertThat(OtpHasher.matches("424242", salt, null)).isFalse();
    }
}
