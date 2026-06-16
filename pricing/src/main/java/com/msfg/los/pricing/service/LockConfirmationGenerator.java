package com.msfg.los.pricing.service;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.pricing.domain.PricingAdjustment;
import com.msfg.los.pricing.domain.RateLock;
import com.msfg.los.platform.text.HtmlText;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/** Renders the Lock Confirmation as self-contained HTML (PDF is a later milestone). */
@Component
public class LockConfirmationGenerator {

    public String generate(Loan loan, RateLock lock, List<PricingAdjustment> adjustments) {
        StringBuilder rows = new StringBuilder();
        for (PricingAdjustment a : adjustments) {
            rows.append("<tr><td>").append(HtmlText.escape(a.getName()))
                .append("</td><td style=\"text-align:right\">").append(a.getAdjustmentPercent())
                .append("</td><td style=\"text-align:right\">").append(a.getDollarAmount())
                .append("</td></tr>");
        }
        String term = loan.getLoanTermMonths() == null ? "" : (loan.getLoanTermMonths() / 12) + " Year";
        String product = (loan.getMortgageType() == null ? "" : loan.getMortgageType().name() + " ") + term;
        return """
                <!doctype html><html><head><meta charset="utf-8"><title>Lock Confirmation</title></head>
                <body style="font-family:sans-serif">
                <h1>Lock Confirmation</h1>
                <p>Loan %s — %s</p>
                <table>
                <tr><td>Lock Status</td><td>Locked</td></tr>
                <tr><td>Interest Rate</td><td>%s</td></tr>
                <tr><td>Commitment Period</td><td>%s Day Lock</td></tr>
                <tr><td>Lock Date</td><td>%s</td></tr>
                <tr><td>Current Expiration</td><td>%s</td></tr>
                <tr><td>Compensation Payer</td><td>%s</td></tr>
                <tr><td>Locked By</td><td>%s</td></tr>
                </table>
                <h2>Pricing Breakdown</h2>
                <table><tr><th>Adjustment Name</th><th>Adjustment %%</th><th>Dollar Amount</th></tr>%s</table>
                <p>Generated %s</p>
                </body></html>
                """.formatted(
                loan.getLoanNumber(), product,
                lock.getLockedRate(), lock.getCommitmentDays(), lock.getLockDate(),
                lock.getExpirationDate(), lock.getCompensationPayerType(),
                HtmlText.escape(lock.getLockedBy()),
                rows, LocalDate.now(ZoneOffset.UTC));
    }
}
