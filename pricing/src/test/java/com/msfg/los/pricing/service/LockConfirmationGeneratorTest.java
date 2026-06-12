package com.msfg.los.pricing.service;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.pricing.domain.PricingAdjustment;
import com.msfg.los.pricing.domain.RateLock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LockConfirmationGeneratorTest {

    private final LockConfirmationGenerator generator = new LockConfirmationGenerator();

    // Adjustment names (engine label strings) and lockedBy (caller identity) are not server
    // constants — they must land in the letter as text, never live markup.
    @Test
    void escapesUserOriginatedStrings() {
        Loan loan = new Loan();
        RateLock lock = new RateLock();
        lock.setLockedBy("<script>alert(1)</script>");
        PricingAdjustment adjustment = new PricingAdjustment();
        adjustment.setName("LLPA <img src=x onerror=alert(1)>");

        String html = generator.generate(loan, lock, List.of(adjustment));

        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("LLPA &lt;img src=x onerror=alert(1)&gt;");
        assertThat(html).doesNotContain("<script>");
        assertThat(html).doesNotContain("<img");
    }

    @Test
    void plainNamesRenderUnchanged() {
        Loan loan = new Loan();
        RateLock lock = new RateLock();
        lock.setLockedBy("officer-1");
        PricingAdjustment adjustment = new PricingAdjustment();
        adjustment.setName("Investor SRP");

        String html = generator.generate(loan, lock, List.of(adjustment));

        assertThat(html).contains("officer-1");
        assertThat(html).contains("Investor SRP");
    }
}
