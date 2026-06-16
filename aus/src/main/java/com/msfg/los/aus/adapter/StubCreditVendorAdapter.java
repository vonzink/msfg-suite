package com.msfg.los.aus.adapter;

import com.msfg.los.aus.domain.CreditBureau;
import com.msfg.los.aus.domain.CreditScoreEntry;
import com.msfg.los.aus.service.CreditBorrower;
import com.msfg.los.aus.service.CreditVendorPort;
import com.msfg.los.aus.service.CreditVendorRequest;
import com.msfg.los.aus.service.CreditVendorResult;
import com.msfg.los.aus.service.VendorArtifact;
import com.msfg.los.platform.text.HtmlText;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic stand-in for a credit vendor: same (loanId, providerCode) always yields the same
 * report identifier and scores, so credit ordering + DU/LPA reissue wiring is fully exercisable
 * with no vendor connectivity. Swapped for a real wire adapter per the {@link CreditVendorPort}
 * contract.
 */
@Component
public class StubCreditVendorAdapter implements CreditVendorPort {

    private static final String SCORE_MODEL = "Classic FICO (stub)";

    @Override
    public CreditVendorResult order(CreditVendorRequest request) {
        // Deterministic per (loanId, providerCode): identical requests reproduce the same
        // identifier and score set.
        long seed = request.loanId().getMostSignificantBits()
                ^ (request.providerCode() != null ? request.providerCode().hashCode() : 0);
        Random rnd = new Random(seed);

        // REISSUE travels with the bureau-assigned reference; SUBMIT/FORCE_NEW mint a new one.
        String identifier = request.creditReportIdentifier() != null
                ? request.creditReportIdentifier()
                : "XS-%08d".formatted(Math.abs(rnd.nextLong() % 100_000_000L));

        List<CreditScoreEntry> scores = scores(request, rnd);
        return new CreditVendorResult(identifier, scores, reportHtml(request, scores));
    }

    // One score per selected bureau per borrower — borrowers outer, bureaus inner in
    // EQUIFAX/EXPERIAN/TRANSUNION order. Stub scores land in [660, 790].
    private List<CreditScoreEntry> scores(CreditVendorRequest request, Random rnd) {
        List<CreditBureau> bureaus = selectedBureaus(request);
        List<CreditScoreEntry> scores = new ArrayList<>();
        for (CreditBorrower borrower : request.borrowers()) {
            for (CreditBureau bureau : bureaus) {
                scores.add(new CreditScoreEntry(borrower.borrowerId(), bureau,
                        660 + rnd.nextInt(131), SCORE_MODEL));
            }
        }
        return scores;
    }

    private List<CreditBureau> selectedBureaus(CreditVendorRequest request) {
        List<CreditBureau> bureaus = new ArrayList<>();
        if (request.equifax()) {
            bureaus.add(CreditBureau.EQUIFAX);
        }
        if (request.experian()) {
            bureaus.add(CreditBureau.EXPERIAN);
        }
        if (request.transUnion()) {
            bureaus.add(CreditBureau.TRANSUNION);
        }
        return bureaus;
    }

    // Small templated report: borrower list + per-bureau scores table.
    private VendorArtifact reportHtml(CreditVendorRequest request, List<CreditScoreEntry> scores) {
        StringBuilder borrowerItems = new StringBuilder();
        for (CreditBorrower borrower : request.borrowers()) {
            borrowerItems.append("<li>%s %s</li>".formatted(
                    HtmlText.escape(borrower.firstName()), HtmlText.escape(borrower.lastName())));
        }
        StringBuilder scoreRows = new StringBuilder();
        for (CreditScoreEntry entry : scores) {
            scoreRows.append("<tr><td>%s</td><td>%s</td><td>%d</td></tr>"
                    .formatted(entry.borrowerId(), entry.bureau(), entry.score()));
        }
        String html = """
                <html><body>
                <h1>Credit Report (stub)</h1>
                <ul>%s</ul>
                <table>
                <tr><th>Borrower</th><th>Bureau</th><th>Score</th></tr>
                %s
                </table>
                </body></html>
                """.formatted(borrowerItems, scoreRows);
        return new VendorArtifact("credit-report.html", "text/html", html.getBytes(StandardCharsets.UTF_8));
    }
}
