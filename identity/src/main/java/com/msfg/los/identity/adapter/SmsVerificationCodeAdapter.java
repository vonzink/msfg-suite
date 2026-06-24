package com.msfg.los.identity.adapter;

import com.msfg.los.platform.notify.VerificationChannel;
import com.msfg.los.platform.notify.VerificationCodePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * SMS delivery adapter for staff-initiated borrower verification codes (security spec §6.2/§3.3).
 *
 * <p>DORMANT by default: SMS is ops-gated behind SNS sandbox exit + 10DLC + a {@code MonthlySpendLimit}
 * toll-fraud cap (spec §3.3/§7.5), so this adapter only registers when {@code los.notify.sms=enabled}.
 * Until then no SMS bean exists, the service's channel router finds no adapter for {@code SMS}, and the
 * FE hides the SMS factor — graceful degradation. Mirrors the dormant {@code CognitoUserAdminAdapter}
 * (@ConditionalOnProperty) pattern.
 *
 * <p>SECURITY: when made real, this MUST NOT log the {@code code}; enforce US-only destinations + the
 * per-identifier throttle (spec §7.5).
 */
@Component
@ConditionalOnProperty(name = "los.notify.sms", havingValue = "enabled")
public class SmsVerificationCodeAdapter implements VerificationCodePort {

    private static final Logger log = LoggerFactory.getLogger(SmsVerificationCodeAdapter.class);

    @Override
    public boolean supports(VerificationChannel channel) {
        return channel == VerificationChannel.SMS;
    }

    @Override
    public void send(VerificationChannel channel, String destination, String code) {
        // Real impl (post-P6): SNS Publish to the E.164 destination. Stub: record dispatch (never the code).
        log.info("Dispatched SMS verification code to a borrower contact (channel={})", channel);
    }
}
