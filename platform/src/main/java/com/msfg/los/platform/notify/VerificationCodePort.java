package com.msfg.los.platform.notify;

/**
 * Dispatch seam for a one-time verification code to a borrower's stored contact (security spec §6.2).
 *
 * <p>Provider-neutral, mirroring {@code UserAdminPort}: the suite mints + hashes + rate-limits the code
 * and hands the <em>plaintext</em> code to this port purely for delivery. The default
 * {@code EmailVerificationCodeAdapter} is live; {@code SmsVerificationCodeAdapter} is
 * {@code @ConditionalOnProperty}-dormant until the SMS rails clear ops review. A non-AWS provider
 * (Twilio, SendGrid, SES) just supplies a different adapter — the service is unchanged.
 *
 * <p>SECURITY: implementations MUST NOT log the {@code code}. The caller has already resolved
 * {@code destination} from {@code borrower_party} (cellPhone for SMS, email for EMAIL).
 */
public interface VerificationCodePort {

    /** Whether this adapter handles the given channel. The service routes by this (multiple adapters can coexist). */
    boolean supports(VerificationChannel channel);

    /**
     * Deliver the given plaintext {@code code} to {@code destination} over {@code channel}.
     *
     * @param channel     the delivery channel this adapter handles
     * @param destination the resolved contact (E.164 phone for SMS, email address for EMAIL)
     * @param code        the plaintext one-time code (never persisted in plaintext; never logged)
     */
    void send(VerificationChannel channel, String destination, String code);
}
