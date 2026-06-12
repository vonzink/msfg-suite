package com.msfg.los.pricing.engine;

import com.msfg.los.loan.domain.LoanPurposeType;
import com.msfg.los.pricing.domain.CompensationPayerType;
import com.msfg.los.pricing.domain.PricingRowType;
import com.msfg.los.pricing.port.PricingQuoteRequest;
import com.msfg.los.pricing.port.QuoteRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StubPricingEngineAdapterTest {

    private final StubPricingEngineAdapter engine = new StubPricingEngineAdapter();

    private static PricingQuoteRequest req(String rate, int days, CompensationPayerType comp,
                                           int extDays, Integer fico, String ltv, LoanPurposeType purpose) {
        return new PricingQuoteRequest(new BigDecimal(rate), days, comp, extDays, fico,
                ltv == null ? null : new BigDecimal(ltv), purpose, new BigDecimal("300000"));
    }

    private static void assertRow(QuoteRow row, String name, PricingRowType type, String percent) {
        assertThat(row.name()).isEqualTo(name);
        assertThat(row.rowType()).isEqualTo(type);
        assertThat(row.percent()).isEqualByComparingTo(percent);
    }

    @Test
    void baselinePurchase_fico745_ltv80_30day_lenderPaid() {
        // Base: -((7.000-6.500)*0.500) - ((30-15)/15*0.125) = -0.375
        // FICO/LTV: 745 in 700-759, ltv 80 in (60,80] => 0.500 (boundary: 80 is <=80)
        // Purpose PURCHASE: 0.000 ; Final: 0.125 ; Comp LENDER_PAID: 1.000 ; FAC: 1.125
        List<QuoteRow> rows = engine.quote(
                req("6.500", 30, CompensationPayerType.LENDER_PAID, 0, 745, "80.000", LoanPurposeType.PURCHASE)).rows();
        assertThat(rows).hasSize(6);
        assertRow(rows.get(0), "Base Price", PricingRowType.BASE, "-0.375");
        assertRow(rows.get(1), "FICO/LTV Adjustment", PricingRowType.ADJUSTMENT, "0.500");
        assertRow(rows.get(2), "Purpose Adjustment", PricingRowType.ADJUSTMENT, "0.000");
        assertRow(rows.get(3), "Final Price", PricingRowType.FINAL, "0.125");
        assertRow(rows.get(4), "Compensation", PricingRowType.COMPENSATION, "1.000");
        assertRow(rows.get(5), "Final Price After Compensation", PricingRowType.FINAL_AFTER_COMP, "1.125");
    }

    @Test
    void bestBucket_fico760_ltv60_15day_borrowerPaid() {
        // Base: -((7.000-7.250)*0.500) - 0 = +0.125 ; FICO/LTV >=760 & <=60: 0.000
        // Purpose null: 0.000 ; Final: 0.125 ; Comp BORROWER_PAID: 0.000 ; FAC: 0.125
        List<QuoteRow> rows = engine.quote(
                req("7.250", 15, CompensationPayerType.BORROWER_PAID, 0, 760, "60.000", null)).rows();
        assertRow(rows.get(0), "Base Price", PricingRowType.BASE, "0.125");
        assertRow(rows.get(1), "FICO/LTV Adjustment", PricingRowType.ADJUSTMENT, "0.000");
        assertRow(rows.get(3), "Final Price", PricingRowType.FINAL, "0.125");
        assertRow(rows.get(4), "Compensation", PricingRowType.COMPENSATION, "0.000");
        assertRow(rows.get(5), "Final Price After Compensation", PricingRowType.FINAL_AFTER_COMP, "0.125");
    }

    @Test
    void worstBucket_fico699_ltv81_construction_90day() {
        // Base: -((7.000-6.000)*0.500) - ((90-15)/15*0.125) = -0.500 - 0.625 = -1.125
        // FICO/LTV <700 & >80: 1.500 ; CONSTRUCTION: 0.500 ; Final: 0.875
        List<QuoteRow> rows = engine.quote(
                req("6.000", 90, CompensationPayerType.LENDER_PAID, 0, 699, "81.000", LoanPurposeType.CONSTRUCTION)).rows();
        assertRow(rows.get(0), "Base Price", PricingRowType.BASE, "-1.125");
        assertRow(rows.get(1), "FICO/LTV Adjustment", PricingRowType.ADJUSTMENT, "1.500");
        assertRow(rows.get(2), "Purpose Adjustment", PricingRowType.ADJUSTMENT, "0.500");
        assertRow(rows.get(3), "Final Price", PricingRowType.FINAL, "0.875");
    }

    @Test
    void refinancePurpose_addsQuarterPoint() {
        List<QuoteRow> rows = engine.quote(
                req("6.500", 30, CompensationPayerType.LENDER_PAID, 0, 745, "80.000", LoanPurposeType.REFINANCE)).rows();
        assertRow(rows.get(2), "Purpose Adjustment", PricingRowType.ADJUSTMENT, "0.250");
        assertRow(rows.get(3), "Final Price", PricingRowType.FINAL, "0.375");
    }

    @Test
    void nullFicoOrLtv_usesNoDataRowAtZero() {
        List<QuoteRow> rows = engine.quote(
                req("6.500", 30, CompensationPayerType.LENDER_PAID, 0, null, "80.000", LoanPurposeType.PURCHASE)).rows();
        assertRow(rows.get(1), "FICO/LTV Adjustment (no data)", PricingRowType.ADJUSTMENT, "0.000");
    }

    @Test
    void extensionDays_emitExtensionFeeRowBeforeFinal() {
        // Extension Fee (15 days) = 15 * 0.020 = 0.300 ; Final = -0.375 + 0.500 + 0 + 0.300 = 0.425
        List<QuoteRow> rows = engine.quote(
                req("6.500", 30, CompensationPayerType.LENDER_PAID, 15, 745, "80.000", LoanPurposeType.PURCHASE)).rows();
        assertThat(rows).hasSize(7);
        assertRow(rows.get(3), "Extension Fee (15 days)", PricingRowType.ADJUSTMENT, "0.300");
        assertRow(rows.get(4), "Final Price", PricingRowType.FINAL, "0.425");
        assertRow(rows.get(6), "Final Price After Compensation", PricingRowType.FINAL_AFTER_COMP, "1.425");
    }

    @Test
    void ltvBoundary60_isInBestColumn() {
        // ltv exactly 60 => "<= 60" column ; fico 700-759 => 0.250
        List<QuoteRow> rows = engine.quote(
                req("6.500", 30, CompensationPayerType.LENDER_PAID, 0, 700, "60.000", LoanPurposeType.PURCHASE)).rows();
        assertRow(rows.get(1), "FICO/LTV Adjustment", PricingRowType.ADJUSTMENT, "0.250");
    }
}
