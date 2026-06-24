package com.msfg.los.platform.notify;

/**
 * The delivery channel for a staff-initiated borrower verification code (security spec §6.2).
 *
 * <p>{@code EMAIL} is live day-one (Cognito {@code COGNITO_DEFAULT}/SES email rails); {@code SMS} is
 * fully designed but ops-gated behind SNS sandbox exit + 10DLC, so the {@code SmsVerificationCodeAdapter}
 * stays dormant ({@code @ConditionalOnProperty}) until the SMS rails are live.
 */
public enum VerificationChannel {
    EMAIL,
    SMS
}
