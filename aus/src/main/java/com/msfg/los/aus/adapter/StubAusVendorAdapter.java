package com.msfg.los.aus.adapter;

import com.msfg.los.aus.domain.AusRecommendation;
import com.msfg.los.aus.domain.AusVendor;
import com.msfg.los.aus.service.AusLoanFile;
import com.msfg.los.aus.service.AusSubmission;
import com.msfg.los.aus.service.AusVendorPort;
import com.msfg.los.aus.service.AusVendorResult;
import com.msfg.los.aus.service.CreditWiring;
import com.msfg.los.aus.service.VendorArtifact;
import com.msfg.los.platform.text.HtmlText;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic stand-in for DU/LPA: same (loanId, vendor) always yields the same casefile id and
 * findings, so the rest of the AUS flow (runs, artifacts, documents) is fully exercisable with no
 * vendor connectivity. Swapped for real wire adapters per the {@link AusVendorPort} contract.
 */
@Component
public class StubAusVendorAdapter implements AusVendorPort {

    /** Stub eligibility cap: LTV above 97% lands in the INELIGIBLE tier. */
    private static final BigDecimal MAX_ELIGIBLE_LTV_PERCENT = new BigDecimal("97");

    @Override
    public AusVendorResult submit(AusSubmission submission) {
        // Deterministic per (loanId, vendor): resubmits of the same loan to the same vendor
        // reproduce the same casefile id — mirrors DU casefile-id reuse semantics.
        Random rnd = new Random(submission.loanId().getMostSignificantBits() ^ submission.vendor().ordinal());

        // First submission mints the vendor case id; resubmits echo the existing one.
        String caseId = submission.existingCaseId() != null
                ? submission.existingCaseId()
                : mintCaseId(submission.vendor().name(), rnd);

        // LPA assigns a transaction number on every submission; DU has no equivalent (null).
        String transactionId = submission.vendor() == AusVendor.LPA
                ? "TX-%08d".formatted(Math.abs(rnd.nextLong() % 100_000_000L))
                : null;

        AusLoanFile file = submission.loanFile();
        boolean incomplete = isIncomplete(file);
        BigDecimal ltvPercent = incomplete ? null : ltvPercent(file);

        List<String> messages = new ArrayList<>();
        if (incomplete) {
            messages.add("Loan file is incomplete: note amount, interest rate, property value and "
                    + "at least one borrower are required.");
        } else {
            messages.add("LTV computed as " + ltvPercent + "% (stub eligibility cap "
                    + MAX_ELIGIBLE_LTV_PERCENT + "%).");
        }

        Decision decision = submission.vendor() == AusVendor.DU
                ? decideDu(incomplete, ltvPercent)
                : decideLpa(incomplete, ltvPercent);
        messages.add("Recommendation: " + decision.rawRecommendation());

        List<VendorArtifact> artifacts = List.of(
                findingsHtml(submission, caseId, decision, messages),
                findingsXml(caseId, decision));

        return new AusVendorResult(caseId, transactionId, decision.recommendation(),
                decision.rawRecommendation(), decision.rawEligibility(), messages, artifacts);
    }

    /** Normalized decision + the raw vendor strings stored alongside it. */
    private record Decision(AusRecommendation recommendation, String rawRecommendation, String rawEligibility) {}

    // DU tiers (stub): incomplete file -> Out of Scope; LTV over the cap -> Approve/Ineligible;
    // otherwise Approve/Eligible.
    private Decision decideDu(boolean incomplete, BigDecimal ltvPercent) {
        if (incomplete) {
            return new Decision(AusRecommendation.OUT_OF_SCOPE, "Out of Scope", null);
        }
        if (exceedsMaxLtv(ltvPercent)) {
            return new Decision(AusRecommendation.APPROVE_INELIGIBLE, "Approve/Ineligible", null);
        }
        return new Decision(AusRecommendation.APPROVE_ELIGIBLE, "Approve/Eligible", null);
    }

    // LPA tiers (stub): risk class + purchase eligibility. Incomplete file or LTV over the cap
    // -> Caution / Freddie Mac Ineligible; otherwise Accept / Freddie Mac Eligible.
    private Decision decideLpa(boolean incomplete, BigDecimal ltvPercent) {
        if (incomplete || exceedsMaxLtv(ltvPercent)) {
            return new Decision(AusRecommendation.CAUTION, "Caution", "Freddie Mac Ineligible");
        }
        return new Decision(AusRecommendation.ACCEPT, "Accept", "Freddie Mac Eligible");
    }

    // Incomplete = missing any field the stub decision needs. A null/zero property value counts as
    // incomplete too (LTV is undefined), so the math below never divides by zero.
    private boolean isIncomplete(AusLoanFile file) {
        return file.noteAmount() == null
                || file.interestRate() == null
                || file.borrowerCount() == 0
                || file.propertyValue() == null
                || file.propertyValue().signum() == 0;
    }

    // LTV % = note / value * 100, scale 4, HALF_UP. Only called on complete files.
    private BigDecimal ltvPercent(AusLoanFile file) {
        return file.noteAmount()
                .multiply(BigDecimal.valueOf(100))
                .divide(file.propertyValue(), 4, RoundingMode.HALF_UP);
    }

    private boolean exceedsMaxLtv(BigDecimal ltvPercent) {
        return ltvPercent.compareTo(MAX_ELIGIBLE_LTV_PERCENT) > 0;
    }

    // Deterministic vendor case id, e.g. DU-0123456789 / LPA-0123456789.
    private String mintCaseId(String prefix, Random rnd) {
        return "%s-%010d".formatted(prefix, Math.abs(rnd.nextLong() % 10_000_000_000L));
    }

    private VendorArtifact findingsHtml(AusSubmission submission, String caseId, Decision decision,
            List<String> messages) {
        StringBuilder items = new StringBuilder();
        for (String message : messages) {
            items.append("<li>").append(message).append("</li>");
        }
        String html = """
                <html><body>
                <h1>%s Findings (stub)</h1>
                <p>Recommendation: %s</p>
                <p>Casefile: %s</p>
                <ul>%s</ul>
                <p>Credit wiring: %s</p>
                </body></html>
                """.formatted(submission.vendor().name(), decision.rawRecommendation(), caseId,
                items, wiringSummary(submission.creditWiring()));
        return new VendorArtifact("findings.html", "text/html", html.getBytes(StandardCharsets.UTF_8));
    }

    private VendorArtifact findingsXml(String caseId, Decision decision) {
        String xml = "<Findings><Recommendation>%s</Recommendation><CasefileId>%s</CasefileId></Findings>"
                .formatted(decision.rawRecommendation(), caseId);
        return new VendorArtifact("findings.xml", "application/xml", xml.getBytes(StandardCharsets.UTF_8));
    }

    // One-line summary of the credit wiring carried into the submission (provider + wired borrowers).
    private String wiringSummary(CreditWiring wiring) {
        if (wiring == null) {
            return "none";
        }
        String provider = wiring.creditProviderCode() != null
                ? HtmlText.escape(wiring.creditProviderCode())
                : "none";
        int wired = wiring.perBorrower() != null ? wiring.perBorrower().size() : 0;
        return "provider=" + provider + ", borrowers wired=" + wired;
    }
}
