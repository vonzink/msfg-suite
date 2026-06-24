package com.msfg.los.identity.adapter;

import com.msfg.los.platform.notify.VerificationChannel;
import com.msfg.los.platform.notify.VerificationCodePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Day-one EMAIL delivery adapter for staff-initiated borrower verification codes (security spec §6.2).
 * Always registered (no {@code @ConditionalOnProperty}), so EMAIL works out of the box — mirroring how
 * {@code StubUserAdminAdapter} is the default. A real transactional-email provider (SES/SendGrid) swaps
 * in here behind the same {@link VerificationCodePort} with zero service change.
 *
 * <p>This stub logs that a code was dispatched (destination + channel only) so local/test flows are
 * observable. SECURITY: it NEVER logs the {@code code} itself.
 */
@Component
public class EmailVerificationCodeAdapter implements VerificationCodePort {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationCodeAdapter.class);

    @Override
    public boolean supports(VerificationChannel channel) {
        return channel == VerificationChannel.EMAIL;
    }

    @Override
    public void send(VerificationChannel channel, String destination, String code) {
        // Real impl: render + send the OTP email. Stub: record dispatch (never the code).
        log.info("Dispatched EMAIL verification code to a borrower contact (channel={})", channel);
    }
}
