package com.msfg.los.aus.adapter;

import com.msfg.los.aus.domain.CreditBureau;
import com.msfg.los.aus.domain.CreditOrderAction;
import com.msfg.los.aus.domain.CreditRequestType;
import com.msfg.los.aus.service.CreditBorrower;
import com.msfg.los.aus.service.CreditVendorRequest;
import com.msfg.los.aus.service.CreditVendorResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StubCreditVendorAdapterTest {

    private final StubCreditVendorAdapter adapter = new StubCreditVendorAdapter();

    private final UUID loanId = UUID.randomUUID();
    private final UUID borrower1 = UUID.randomUUID();
    private final UUID borrower2 = UUID.randomUUID();

    private List<CreditBorrower> borrowers() {
        return List.of(new CreditBorrower(borrower1, "Alice", "Anderson"),
                new CreditBorrower(borrower2, "Bob", "Baker"));
    }

    private CreditVendorRequest tripleMergeSubmit() {
        return new CreditVendorRequest(loanId, "1", CreditOrderAction.SUBMIT, CreditRequestType.JOINT,
                true, true, true, borrowers(), null);
    }

    @Test
    void mintsIdentifierWithPrefix() {
        CreditVendorResult first = adapter.order(tripleMergeSubmit());
        CreditVendorResult second = adapter.order(tripleMergeSubmit());

        // Spec (stub adapters): credit refs are XS- + 8 digits.
        assertThat(first.creditReportIdentifier()).matches("XS-\\d{8}");
        assertThat(second.creditReportIdentifier()).isEqualTo(first.creditReportIdentifier());
    }

    @Test
    void forceNewMintsIdentifier() {
        CreditVendorRequest forceNew = new CreditVendorRequest(loanId, "1", CreditOrderAction.FORCE_NEW,
                CreditRequestType.JOINT, true, true, true, borrowers(), null);

        CreditVendorResult first = adapter.order(forceNew);
        CreditVendorResult second = adapter.order(forceNew);

        assertThat(first.creditReportIdentifier()).startsWith("XS-");
        assertThat(second.creditReportIdentifier()).isEqualTo(first.creditReportIdentifier());
    }

    @Test
    void scoresPerBorrowerPerBureau() {
        CreditVendorResult first = adapter.order(tripleMergeSubmit());
        CreditVendorResult second = adapter.order(tripleMergeSubmit());

        assertThat(first.scores()).hasSize(6); // 2 borrowers x 3 bureaus
        assertThat(first.scores()).allSatisfy(entry -> {
            assertThat(entry.score()).isBetween(660, 790);
            assertThat(entry.bureau()).isNotNull();
            assertThat(entry.borrowerId()).isNotNull();
            assertThat(entry.model()).isNotBlank();
        });
        assertThat(second.scores()).isEqualTo(first.scores());
    }

    @Test
    void bureauSelectionRespected() {
        CreditVendorRequest equifaxOnly = new CreditVendorRequest(loanId, "1", CreditOrderAction.SUBMIT,
                CreditRequestType.JOINT, true, false, false, borrowers(), null);

        CreditVendorResult result = adapter.order(equifaxOnly);

        assertThat(result.scores()).hasSize(2);
        assertThat(result.scores()).allSatisfy(entry ->
                assertThat(entry.bureau()).isEqualTo(CreditBureau.EQUIFAX));
    }

    @Test
    void reissueEchoesIdentifier() {
        CreditVendorRequest reissue = new CreditVendorRequest(loanId, "1", CreditOrderAction.REISSUE,
                CreditRequestType.JOINT, true, true, true, borrowers(), "XS-EXISTING1");

        assertThat(adapter.order(reissue).creditReportIdentifier()).isEqualTo("XS-EXISTING1");
    }

    @Test
    void reportArtifact() {
        CreditVendorResult result = adapter.order(tripleMergeSubmit());

        assertThat(result.report().contentType()).isEqualTo("text/html");
        String body = new String(result.report().bytes(), StandardCharsets.UTF_8);
        assertThat(body).contains("Alice");
        assertThat(body).contains("Bob");
    }
}
