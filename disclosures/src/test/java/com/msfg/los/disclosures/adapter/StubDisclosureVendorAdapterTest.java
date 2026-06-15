package com.msfg.los.disclosures.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.msfg.los.disclosures.domain.DeliveryMethod;
import com.msfg.los.disclosures.domain.DisclosureKind;
import com.msfg.los.disclosures.domain.DisclosureStatus;
import com.msfg.los.disclosures.domain.ReceivedBasis;
import com.msfg.los.disclosures.service.DeliveryRequest;
import com.msfg.los.disclosures.service.DeliveryResult;
import com.msfg.los.disclosures.service.DeliveryStatus;
import com.msfg.los.disclosures.service.DisclosureGenerationRequest;
import com.msfg.los.disclosures.service.DisclosureGenerationResult;
import com.msfg.los.disclosures.service.UcdExportResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StubDisclosureVendorAdapterTest {

    private final StubDisclosureVendorAdapter adapter = new StubDisclosureVendorAdapter();

    private static final UUID LOAN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ee");

    private DisclosureGenerationRequest request(BigDecimal prepaidFinanceCharges) {
        return new DisclosureGenerationRequest(
                DisclosureKind.LOAN_ESTIMATE,
                LOAN_ID,
                1,
                "LN-12345 <script>",
                new BigDecimal("300000.00"),
                new BigDecimal("6.5"),
                360,
                new BigDecimal("1896.20"),
                new BigDecimal("8500.00"),
                prepaidFinanceCharges,
                false,
                "30-Year Fixed",
                List.of(),
                List.of());
    }

    @Test
    void generateIsDeterministicForSameInputs() {
        DisclosureGenerationResult a = adapter.generate(request(new BigDecimal("4200.00")));
        DisclosureGenerationResult b = adapter.generate(request(new BigDecimal("4200.00")));

        assertThat(a.vendorReference()).isEqualTo(b.vendorReference());
        assertThat(a.apr()).isEqualByComparingTo(b.apr());
    }

    @Test
    void vendorReferenceMatchesDvEightDigits() {
        DisclosureGenerationResult result = adapter.generate(request(new BigDecimal("4200.00")));
        assertThat(result.vendorReference()).matches("DV-\\d{8}");
    }

    @Test
    void higherPrepaidFinanceChargesYieldStrictlyHigherApr() {
        DisclosureGenerationResult low = adapter.generate(request(new BigDecimal("1000.00")));
        DisclosureGenerationResult high = adapter.generate(request(new BigDecimal("9000.00")));

        assertThat(high.apr()).isGreaterThan(low.apr());
    }

    @Test
    void amountFinancedIsLoanAmountMinusPrepaidFinanceCharges() {
        DisclosureGenerationResult result = adapter.generate(request(new BigDecimal("4200.00")));
        assertThat(result.amountFinanced())
                .isEqualByComparingTo(new BigDecimal("300000.00").subtract(new BigDecimal("4200.00")));
    }

    @Test
    void financeChargeIsNonNegative() {
        DisclosureGenerationResult result = adapter.generate(request(new BigDecimal("4200.00")));
        assertThat(result.financeCharge()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void aprIrregularBasisIsFalse() {
        DisclosureGenerationResult result = adapter.generate(request(new BigDecimal("4200.00")));
        assertThat(result.aprIrregularBasis()).isFalse();
    }

    @Test
    void renderedFormIsHtmlAndContainsLoanNumberAndPlaceholder() {
        DisclosureGenerationResult result = adapter.generate(request(new BigDecimal("4200.00")));
        assertThat(result.renderedContentType()).isEqualTo("text/html");

        String html = new String(result.renderedBytes(), StandardCharsets.UTF_8);
        assertThat(html).contains("LN-12345");
        assertThat(html).contains("PLACEHOLDER");
        // user input is HTML-escaped (no raw script tag leaks)
        assertThat(html).doesNotContain("<script>");
    }

    @Test
    void sendReturnsConstructivePlus3WithDvReference() {
        DeliveryResult result = adapter.send(new DeliveryRequest(
                LOAN_ID, UUID.randomUUID(), DisclosureKind.LOAN_ESTIMATE, DeliveryMethod.EMAIL));

        assertThat(result.basis()).isEqualTo(ReceivedBasis.CONSTRUCTIVE_PLUS_3);
        assertThat(result.vendorReference()).startsWith("DV-");
    }

    @Test
    void getStatusReturnsSentWithNullActualReceipt() {
        DeliveryStatus status = adapter.getStatus("DV-12345678");
        assertThat(status.status()).isEqualTo(DisclosureStatus.SENT);
        assertThat(status.actualReceiptDate()).isNull();
    }

    @Test
    void exportUcdReturnsMismoVersion330() {
        UcdExportResult result = adapter.exportUcd(LOAN_ID, UUID.randomUUID());
        assertThat(result.mismoVersion()).isEqualTo("3.3.0");
        assertThat(result.vendorReference()).startsWith("DV-");
        assertThat(result.ucdXml()).isNotEmpty();
    }
}
