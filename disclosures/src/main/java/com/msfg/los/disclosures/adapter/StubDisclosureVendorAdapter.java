package com.msfg.los.disclosures.adapter;

import com.msfg.los.disclosures.domain.DisclosureStatus;
import com.msfg.los.disclosures.domain.ReceivedBasis;
import com.msfg.los.disclosures.service.DeliveryRequest;
import com.msfg.los.disclosures.service.DeliveryResult;
import com.msfg.los.disclosures.service.DeliveryStatus;
import com.msfg.los.disclosures.service.DisclosureGenerationRequest;
import com.msfg.los.disclosures.service.DisclosureGenerationResult;
import com.msfg.los.disclosures.service.DisclosureVendorPort;
import com.msfg.los.disclosures.service.UcdExportResult;
import com.msfg.los.platform.text.HtmlText;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deterministic, dependency-free stub for {@link DisclosureVendorPort}. It exists so the
 * disclosures module is fully exercisable without a live DocMagic/IDS/Docutech account, and so a
 * real adapter is a drop-in swap.
 *
 * <p><strong>The stub's APR is NOT an Appendix-J actuarial computation.</strong> It is a monotone
 * placeholder (rate plus a prepaid-finance-charge premium) chosen only so higher prepaids produce a
 * strictly higher APR for testing. The real adapter returns the regulated H-24/H-25 PDF and a
 * vendor-computed APR; the creditor remains liable for accuracy.
 */
@Component
public class StubDisclosureVendorAdapter implements DisclosureVendorPort {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Override
    public DisclosureGenerationResult generate(DisclosureGenerationRequest request) {
        Random rnd = seededRandom(request.loanId(), request.kind().ordinal(), request.version());
        String vendorReference = mintReference(rnd);

        BigDecimal loanAmount = nz(request.loanAmount());
        BigDecimal prepaid = nz(request.prepaidFinanceCharges());

        // Monotone placeholder APR: interest rate + (prepaid / loanAmount) * 100.
        // Higher prepaidFinanceCharges => strictly higher APR. NOT Appendix-J actuarial.
        BigDecimal premium = prepaid
                .divide(loanAmount.max(BigDecimal.ONE), 10, RoundingMode.HALF_UP)
                .multiply(HUNDRED);
        BigDecimal apr = nz(request.interestRate()).add(premium).setScale(5, RoundingMode.HALF_UP);

        BigDecimal amountFinanced = loanAmount.subtract(prepaid).setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalOfPayments;
        if (request.termMonths() == null || request.monthlyPrincipalInterest() == null) {
            totalOfPayments = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        } else {
            totalOfPayments = request.monthlyPrincipalInterest()
                    .multiply(new BigDecimal(request.termMonths()))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal financeCharge =
                totalOfPayments.subtract(amountFinanced).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        BigDecimal tip;
        if (loanAmount.signum() == 0) {
            tip = BigDecimal.ZERO.setScale(5, RoundingMode.HALF_UP);
        } else {
            tip = financeCharge
                    .divide(loanAmount, 10, RoundingMode.HALF_UP)
                    .multiply(HUNDRED)
                    .setScale(5, RoundingMode.HALF_UP);
        }

        byte[] rendered = renderPlaceholder(request.loanNumber());

        return new DisclosureGenerationResult(
                apr,
                financeCharge,
                amountFinanced,
                totalOfPayments,
                tip,
                false,
                rendered,
                "text/html",
                vendorReference);
    }

    @Override
    public DeliveryResult send(DeliveryRequest request) {
        Random rnd = seededRandom(request.loanId(), request.kind().ordinal(), 0);
        return new DeliveryResult(mintReference(rnd), ReceivedBasis.CONSTRUCTIVE_PLUS_3);
    }

    @Override
    public DeliveryStatus getStatus(String vendorReference) {
        return new DeliveryStatus(DisclosureStatus.SENT, null);
    }

    @Override
    public UcdExportResult exportUcd(UUID loanId, UUID disclosureId) {
        Random rnd = seededRandom(loanId, 0, 0);
        byte[] xml = ("<UCD version=\"3.3.0\">PLACEHOLDER — deferred, not a conforming MISMO export</UCD>")
                .getBytes(StandardCharsets.UTF_8);
        return new UcdExportResult(mintReference(rnd), "3.3.0", xml);
    }

    private static Random seededRandom(UUID loanId, int kindOrdinal, int version) {
        return new Random(loanId.getMostSignificantBits() ^ kindOrdinal ^ version);
    }

    private static String mintReference(Random rnd) {
        return "DV-" + String.format("%08d", Math.abs(rnd.nextLong() % 100_000_000L));
    }

    private static byte[] renderPlaceholder(String loanNumber) {
        String safeLoanNumber = HtmlText.escape(loanNumber);
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Disclosure</title></head>"
                + "<body><h1>Disclosure — Loan " + safeLoanNumber + "</h1>"
                + "<p>PLACEHOLDER — not a conforming H-24/H-25</p></body></html>";
        return html.getBytes(StandardCharsets.UTF_8);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
