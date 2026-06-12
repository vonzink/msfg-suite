package com.msfg.los.aus.adapter;

import com.msfg.los.aus.domain.AusRecommendation;
import com.msfg.los.aus.domain.AusVendor;
import com.msfg.los.aus.domain.CredentialSource;
import com.msfg.los.aus.service.AusLoanFile;
import com.msfg.los.aus.service.AusSubmission;
import com.msfg.los.aus.service.AusVendorResult;
import com.msfg.los.aus.service.CreditWiring;
import com.msfg.los.aus.service.ResolvedCredentials;
import com.msfg.los.aus.service.VendorArtifact;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StubAusVendorAdapterTest {

    private final StubAusVendorAdapter adapter = new StubAusVendorAdapter();

    private AusLoanFile completeFile() {
        return new AusLoanFile("LN-1", new BigDecimal("300000"), new BigDecimal("400000"),
                new BigDecimal("6.5"), 360, 1, null);
    }

    private AusSubmission submission(AusVendor vendor, UUID loanId, AusLoanFile loanFile) {
        ResolvedCredentials credentials = new ResolvedCredentials(CredentialSource.ORG,
                "INST-1", null, null, null, "user", "pass", null, null, null, null);
        return new AusSubmission(vendor, loanId, null, credentials,
                new CreditWiring(null, null, List.of()), loanFile);
    }

    @Test
    void determinismSameCaseIdPerLoanAndVendor() {
        UUID loanId = UUID.randomUUID();
        AusVendorResult du1 = adapter.submit(submission(AusVendor.DU, loanId, completeFile()));
        AusVendorResult du2 = adapter.submit(submission(AusVendor.DU, loanId, completeFile()));
        AusVendorResult lpa = adapter.submit(submission(AusVendor.LPA, loanId, completeFile()));

        assertThat(du1.vendorCaseId()).isEqualTo(du2.vendorCaseId());
        assertThat(lpa.vendorCaseId()).isNotEqualTo(du1.vendorCaseId());
    }

    @Test
    void prefixes() {
        UUID loanId = UUID.randomUUID();
        assertThat(adapter.submit(submission(AusVendor.DU, loanId, completeFile())).vendorCaseId())
                .startsWith("DU-");
        assertThat(adapter.submit(submission(AusVendor.LPA, loanId, completeFile())).vendorCaseId())
                .startsWith("LPA-");
    }

    @Test
    void existingCaseIdEchoed() {
        ResolvedCredentials credentials = new ResolvedCredentials(CredentialSource.ORG,
                "INST-1", null, null, null, "user", "pass", null, null, null, null);
        AusSubmission resubmit = new AusSubmission(AusVendor.DU, UUID.randomUUID(), "DU-123",
                credentials, new CreditWiring(null, null, List.of()), completeFile());

        assertThat(adapter.submit(resubmit).vendorCaseId()).isEqualTo("DU-123");
    }

    @Test
    void completeFileLowLtv() {
        UUID loanId = UUID.randomUUID();
        AusVendorResult du = adapter.submit(submission(AusVendor.DU, loanId, completeFile()));
        assertThat(du.recommendation()).isEqualTo(AusRecommendation.APPROVE_ELIGIBLE);
        assertThat(du.rawRecommendation()).isEqualTo("Approve/Eligible");

        AusVendorResult lpa = adapter.submit(submission(AusVendor.LPA, loanId, completeFile()));
        assertThat(lpa.recommendation()).isEqualTo(AusRecommendation.ACCEPT);
        assertThat(lpa.rawRecommendation()).isEqualTo("Accept");
        assertThat(lpa.rawEligibility()).isEqualTo("Freddie Mac Eligible");
    }

    @Test
    void highLtv() {
        AusLoanFile highLtvFile = new AusLoanFile("LN-1", new BigDecimal("400000"), new BigDecimal("380000"),
                new BigDecimal("6.5"), 360, 1, null);
        UUID loanId = UUID.randomUUID();

        assertThat(adapter.submit(submission(AusVendor.DU, loanId, highLtvFile)).recommendation())
                .isEqualTo(AusRecommendation.APPROVE_INELIGIBLE);
        assertThat(adapter.submit(submission(AusVendor.LPA, loanId, highLtvFile)).recommendation())
                .isEqualTo(AusRecommendation.CAUTION);
    }

    // Every incomplete-file shape must decide (DU Out of Scope, LPA Caution/Freddie Mac Ineligible)
    // and never throw — submit() returning normally IS the no-exception assertion.
    private void assertIncompleteHandling(AusLoanFile incomplete) {
        UUID loanId = UUID.randomUUID();

        assertThat(adapter.submit(submission(AusVendor.DU, loanId, incomplete)).recommendation())
                .isEqualTo(AusRecommendation.OUT_OF_SCOPE);

        AusVendorResult lpa = adapter.submit(submission(AusVendor.LPA, loanId, incomplete));
        assertThat(lpa.recommendation()).isEqualTo(AusRecommendation.CAUTION);
        assertThat(lpa.messages()).anySatisfy(message -> assertThat(message).contains("incomplete"));
        assertThat(lpa.rawEligibility()).isEqualTo("Freddie Mac Ineligible");
    }

    @Test
    void incompleteFileNullNoteAmount() {
        assertIncompleteHandling(new AusLoanFile("LN-1", null, new BigDecimal("400000"),
                new BigDecimal("6.5"), 360, 1, null));
    }

    @Test
    void incompleteFileNullInterestRate() {
        assertIncompleteHandling(new AusLoanFile("LN-1", new BigDecimal("300000"),
                new BigDecimal("400000"), null, 360, 1, null));
    }

    @Test
    void incompleteFileZeroBorrowers() {
        assertIncompleteHandling(new AusLoanFile("LN-1", new BigDecimal("300000"),
                new BigDecimal("400000"), new BigDecimal("6.5"), 360, 0, null));
    }

    @Test
    void incompleteFileNullOrZeroPropertyValue() {
        assertIncompleteHandling(new AusLoanFile("LN-1", new BigDecimal("300000"), null,
                new BigDecimal("6.5"), 360, 1, null));
        // Zero (not just null) value is the divide-by-zero guard on the LTV math.
        assertIncompleteHandling(new AusLoanFile("LN-1", new BigDecimal("300000"), BigDecimal.ZERO,
                new BigDecimal("6.5"), 360, 1, null));
    }

    @Test
    void artifacts() {
        AusVendorResult result = adapter.submit(submission(AusVendor.DU, UUID.randomUUID(), completeFile()));

        assertThat(result.artifacts()).hasSize(2);

        VendorArtifact html = result.artifacts().stream()
                .filter(a -> a.name().equals("findings.html")).findFirst().orElseThrow();
        assertThat(html.contentType()).isEqualTo("text/html");
        String htmlBody = new String(html.bytes(), StandardCharsets.UTF_8);
        assertThat(htmlBody).contains(result.rawRecommendation());
        assertThat(htmlBody).contains(result.vendorCaseId());

        VendorArtifact xml = result.artifacts().stream()
                .filter(a -> a.name().equals("findings.xml")).findFirst().orElseThrow();
        assertThat(xml.contentType()).isEqualTo("application/xml");
        assertThat(new String(xml.bytes(), StandardCharsets.UTF_8)).contains("<Recommendation>");
    }

    // The credit provider code is user input carried into the findings wiring summary —
    // it must land as text, never live markup.
    @Test
    void findingsEscapeHtmlInCreditProviderCode() {
        ResolvedCredentials credentials = new ResolvedCredentials(CredentialSource.ORG,
                "INST-1", null, null, null, "user", "pass", null, null, null, null);
        AusSubmission hostile = new AusSubmission(AusVendor.DU, UUID.randomUUID(), null, credentials,
                new CreditWiring("<img src=x onerror=alert(1)>", null, List.of()), completeFile());

        VendorArtifact html = adapter.submit(hostile).artifacts().stream()
                .filter(a -> a.name().equals("findings.html")).findFirst().orElseThrow();
        String body = new String(html.bytes(), StandardCharsets.UTF_8);

        assertThat(body).contains("&lt;img src=x onerror=alert(1)&gt;");
        assertThat(body).doesNotContain("<img");
    }

    @Test
    void lpaTransactionIdPresent() {
        UUID loanId = UUID.randomUUID();
        AusVendorResult lpa = adapter.submit(submission(AusVendor.LPA, loanId, completeFile()));
        assertThat(lpa.vendorTransactionId()).isNotNull();
        // DU's vendorTransactionId may be null — no assertion.
    }
}
