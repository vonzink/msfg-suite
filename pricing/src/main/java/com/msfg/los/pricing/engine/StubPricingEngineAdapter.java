package com.msfg.los.pricing.engine;

import com.msfg.los.loan.domain.LoanPurposeType;
import com.msfg.los.pricing.domain.CompensationPayerType;
import com.msfg.los.pricing.domain.PricingRowType;
import com.msfg.los.pricing.port.PriceQuote;
import com.msfg.los.pricing.port.PricingEnginePort;
import com.msfg.los.pricing.port.PricingQuoteRequest;
import com.msfg.los.pricing.port.QuoteRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic STUB pricing engine (spec 2026-06-10 §5). Not real pricing —
 * a real vendor adapter replaces this behind the same port, zero service change.
 */
@Component
public class StubPricingEngineAdapter implements PricingEnginePort {

    private static final BigDecimal PAR_RATE = new BigDecimal("7.000");

    @Override
    public PriceQuote quote(PricingQuoteRequest req) {
        List<QuoteRow> rows = new ArrayList<>();

        // 1. Base Price: -((7.000 - rate) * 0.500) - ((commitmentDays - 15) / 15 * 0.125)
        BigDecimal rateComponent = PAR_RATE.subtract(req.rate()).multiply(new BigDecimal("0.500"));
        BigDecimal termComponent = new BigDecimal(req.commitmentDays() - 15)
                .divide(new BigDecimal("15"), 10, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("0.125"));
        rows.add(new QuoteRow("Base Price", PricingRowType.BASE,
                p3(rateComponent.negate().subtract(termComponent))));

        // 2. FICO/LTV bucket
        if (req.fico() == null || req.ltv() == null) {
            rows.add(new QuoteRow("FICO/LTV Adjustment (no data)", PricingRowType.ADJUSTMENT, p3(BigDecimal.ZERO)));
        } else {
            rows.add(new QuoteRow("FICO/LTV Adjustment", PricingRowType.ADJUSTMENT,
                    p3(ficoLtvBucket(req.fico(), req.ltv()))));
        }

        // 3. Purpose
        rows.add(new QuoteRow("Purpose Adjustment", PricingRowType.ADJUSTMENT, p3(purposeAdj(req.loanPurpose()))));

        // 4. Extension fee (cumulative), only when present
        if (req.extensionDaysTotal() > 0) {
            BigDecimal fee = new BigDecimal(req.extensionDaysTotal()).multiply(new BigDecimal("0.020"));
            rows.add(new QuoteRow("Extension Fee (" + req.extensionDaysTotal() + " days)",
                    PricingRowType.ADJUSTMENT, p3(fee)));
        }

        // 5. Final Price = sum of everything so far
        BigDecimal finalPrice = rows.stream().map(QuoteRow::percent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        rows.add(new QuoteRow("Final Price", PricingRowType.FINAL, p3(finalPrice)));

        // 6. Compensation
        BigDecimal comp = req.compensationPayerType() == CompensationPayerType.LENDER_PAID
                ? new BigDecimal("1.000") : BigDecimal.ZERO;
        rows.add(new QuoteRow("Compensation", PricingRowType.COMPENSATION, p3(comp)));

        // 7. Final After Comp
        rows.add(new QuoteRow("Final Price After Compensation", PricingRowType.FINAL_AFTER_COMP,
                p3(finalPrice.add(comp))));

        return new PriceQuote(List.copyOf(rows));
    }

    private static BigDecimal ficoLtvBucket(int fico, BigDecimal ltv) {
        boolean ltv60 = ltv.compareTo(new BigDecimal("60")) <= 0;
        boolean ltv80 = ltv.compareTo(new BigDecimal("80")) <= 0;
        if (fico >= 760) return ltv60 ? bd("0.000") : ltv80 ? bd("0.250") : bd("0.375");
        if (fico >= 700) return ltv60 ? bd("0.250") : ltv80 ? bd("0.500") : bd("0.750");
        return ltv60 ? bd("0.500") : ltv80 ? bd("1.000") : bd("1.500");
    }

    private static BigDecimal purposeAdj(LoanPurposeType purpose) {
        if (purpose == LoanPurposeType.REFINANCE) return bd("0.250");
        if (purpose == LoanPurposeType.CONSTRUCTION) return bd("0.500");
        return BigDecimal.ZERO;   // PURCHASE / OTHER / null
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static BigDecimal p3(BigDecimal v) { return v.setScale(3, RoundingMode.HALF_UP); }
}
