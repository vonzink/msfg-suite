package com.msfg.los.aus.service;

import com.msfg.los.aus.domain.AusIssueMode;
import com.msfg.los.aus.domain.AusRun;
import com.msfg.los.aus.domain.AusRunStatus;
import com.msfg.los.aus.domain.AusVendor;
import com.msfg.los.aus.domain.AusVendorSettings;
import com.msfg.los.aus.domain.CredentialSource;
import com.msfg.los.aus.domain.CredentialVendor;
import com.msfg.los.aus.domain.CreditOrderAction;
import com.msfg.los.aus.domain.CreditReference;
import com.msfg.los.aus.repo.AusProfileRepository;
import com.msfg.los.aus.repo.AusRunRepository;
import com.msfg.los.aus.web.dto.AusRunResponse;
import com.msfg.los.aus.web.dto.AusRunSelection;
import com.msfg.los.documents.domain.Document;
import com.msfg.los.documents.domain.DocumentType;
import com.msfg.los.documents.service.DocumentService;
import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.domain.SubjectProperty;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.domain.BorrowerParty;
import com.msfg.los.parties.service.BorrowerService;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Orchestrates one AUS run per selected vendor: resolve credentials (409 fail-fast), build the
 * credit wiring (REISSUE refs from the profile, or a real port-backed credit order), snapshot the
 * loan file, submit through {@link AusVendorPort}, store both findings artifacts as loan documents,
 * and persist the run. ONE_CLICK fans out to DU then LPA.
 */
@Service
public class AusRunService {

    private static final AusVendorSettings EMPTY_SETTINGS =
            new AusVendorSettings(null, null, null, null, List.of());

    private final AusRunRepository runs;
    private final AusVendorPort ausVendor;
    private final VendorCredentialService credentials;
    private final AusProfileRepository profiles;
    private final CreditOrderService creditOrders;
    private final DocumentService documentService;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final BorrowerService borrowerService;
    private final CurrentUser currentUser;
    private final AusRunErrorRecorder errorRecorder;

    public AusRunService(AusRunRepository runs,
                         AusVendorPort ausVendor,
                         VendorCredentialService credentials,
                         AusProfileRepository profiles,
                         CreditOrderService creditOrders,
                         DocumentService documentService,
                         LoanService loanService,
                         LoanAccessGuard accessGuard,
                         BorrowerService borrowerService,
                         CurrentUser currentUser,
                         AusRunErrorRecorder errorRecorder) {
        this.runs = runs;
        this.ausVendor = ausVendor;
        this.credentials = credentials;
        this.profiles = profiles;
        this.creditOrders = creditOrders;
        this.documentService = documentService;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.borrowerService = borrowerService;
        this.currentUser = currentUser;
        this.errorRecorder = errorRecorder;
    }

    @Transactional
    public List<AusRunResponse> run(UUID loanId, AusRunSelection selection) {
        // Guard ONCE; every downstream step (credit order, document store) runs after this.
        Loan loan = loanService.get(loanId);
        accessGuard.assertCanAccess(loan);

        List<AusVendor> vendors = selection == AusRunSelection.ONE_CLICK
                ? List.of(AusVendor.DU, AusVendor.LPA)
                : List.of(AusVendor.valueOf(selection.name()));

        AusVendorSettings du = EMPTY_SETTINGS;
        AusVendorSettings lpa = EMPTY_SETTINGS;
        var profile = profiles.findByLoanId(loanId).orElse(null);
        if (profile != null) {
            if (profile.getDuSettings() != null) du = profile.getDuSettings();
            if (profile.getLpaSettings() != null) lpa = profile.getLpaSettings();
        }
        List<BorrowerParty> loanBorrowers = borrowerService.listByLoan(loanId);

        List<AusRunResponse> out = new ArrayList<>();
        for (AusVendor vendor : vendors) {
            out.add(runOne(loan, vendor, vendor == AusVendor.DU ? du : lpa, loanBorrowers));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<AusRunResponse> history(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return runs.findByLoanIdOrderByRequestedAtDescIdDesc(loanId).stream()
                .map(this::toResponse)
                .toList();
    }

    private AusRunResponse runOne(Loan loan, AusVendor vendor, AusVendorSettings settings,
                                  List<BorrowerParty> loanBorrowers) {
        UUID loanId = loan.getId();

        // Credentials are a hard precondition — fail (409) BEFORE any persistence.
        ResolvedCredentials creds = credentials.resolve(loanId, CredentialVendor.valueOf(vendor.name()));
        if (creds.source() == CredentialSource.NONE) {
            throw new MissingCredentialsException(vendor);
        }

        // Credit wiring: REISSUE rides the profile's references; ORDER (or no mode) places a real
        // credit order first and wires every borrower to the minted report.
        ResolvedCredentials creditCreds = credentials.resolve(loanId, CredentialVendor.CREDIT);
        String providerCode = settings.creditProviderCode() != null && !settings.creditProviderCode().isBlank()
                ? settings.creditProviderCode()
                : creditCreds.creditProviderCode();

        CreditWiring wiring;
        String creditReportIdentifier;
        if (settings.issueMode() == AusIssueMode.REISSUE) {
            List<CreditReference> refs = settings.creditReferences() == null
                    ? List.of() : settings.creditReferences();
            if (refs.isEmpty()) {
                throw new ValidationException("creditReferences required for REISSUE mode");
            }
            List<BorrowerCredit> perBorrower = refs.stream()
                    .map(r -> new BorrowerCredit(r.borrowerId(), CreditOrderAction.REISSUE, r.reference()))
                    .toList();
            wiring = new CreditWiring(providerCode, creditCreds.creditAffiliateCode(), perBorrower);
            creditReportIdentifier = refs.get(0).reference();
        } else {
            creditReportIdentifier = creditOrders.orderForAusRun(loanId, providerCode);
            String identifier = creditReportIdentifier;
            List<BorrowerCredit> perBorrower = loanBorrowers.stream()
                    .map(b -> new BorrowerCredit(b.getId(), CreditOrderAction.SUBMIT, identifier))
                    .toList();
            wiring = new CreditWiring(providerCode, creditCreds.creditAffiliateCode(), perBorrower);
        }

        AusLoanFile loanFile = loanFile(loan, settings, loanBorrowers.size());

        // Resubmits reuse the vendor-assigned casefile id from the latest prior run for this
        // vendor that actually got one — ERROR rows carry no vendorCaseId and must not break
        // casefile continuity after a failed submit.
        String existingCaseId = runs
                .findTopByLoanIdAndVendorAndVendorCaseIdIsNotNullOrderByRequestedAtDescIdDesc(loanId, vendor)
                .map(AusRun::getVendorCaseId)
                .orElse(null);

        AusVendorResult result;
        try {
            result = ausVendor.submit(new AusSubmission(
                    vendor, loanId, existingCaseId, creds, wiring, loanFile));
        } catch (RuntimeException ex) {
            // The rethrow marks this transaction rollback-only, so the ERROR audit row is
            // persisted in its own REQUIRES_NEW transaction to survive the rollback.
            errorRecorder.recordError(loanId, vendor, ex.getMessage(), currentUser.id().orElse(null));
            throw ex;
        }

        AusRun run = new AusRun();
        run.setLoanId(loanId);
        run.setVendor(vendor);
        run.setStatus(AusRunStatus.COMPLETE);
        run.setVendorCaseId(result.vendorCaseId());
        run.setVendorTransactionId(result.vendorTransactionId());
        run.setRecommendation(result.recommendation());
        run.setRawRecommendation(result.rawRecommendation());
        run.setRawEligibility(result.rawEligibility());
        run.setCreditReportIdentifier(creditReportIdentifier);
        run.setMessages(new ArrayList<>(result.messages()));
        run.setRequestedBy(currentUser.id().orElse(null));
        run.setRequestedAt(Instant.now());

        // Both findings artifacts become real loan documents before the single save.
        for (VendorArtifact artifact : result.artifacts()) {
            boolean xml = artifact.name() != null && artifact.name().endsWith(".xml");
            String fileName = "%s-findings-%s.%s".formatted(
                    vendor.name().toLowerCase(Locale.ROOT), result.vendorCaseId(), xml ? "xml" : "html");
            Document doc = documentService.storeGenerated(loanId, DocumentType.AUS_FINDINGS,
                    vendor.name(), fileName, artifact.contentType(), artifact.bytes());
            if (xml) {
                run.setFindingsXmlDocumentId(doc.getId());
            } else {
                run.setFindingsHtmlDocumentId(doc.getId());
            }
        }
        return toResponse(runs.save(run));
    }

    /** Loan-file snapshot: appraised value wins over estimated; term getter is loanTermMonths. */
    private AusLoanFile loanFile(Loan loan, AusVendorSettings settings, int borrowerCount) {
        SubjectProperty property = loan.getSubjectProperty();
        BigDecimal value = property == null ? null
                : property.getAppraisedValue() != null ? property.getAppraisedValue()
                : property.getEstimatedValue();
        return new AusLoanFile(loan.getLoanNumber(), loan.getNoteAmount(), value,
                loan.getInterestRate(), loan.getLoanTermMonths(), borrowerCount,
                settings.fhaCaseNumber());
    }

    private AusRunResponse toResponse(AusRun r) {
        return new AusRunResponse(r.getId(), r.getVendor(), r.getStatus(), r.getVendorCaseId(),
                r.getVendorTransactionId(), r.getRecommendation(), r.getRawRecommendation(),
                r.getRawEligibility(), r.getCreditReportIdentifier(), r.getFindingsHtmlDocumentId(),
                r.getFindingsXmlDocumentId(), r.getMessages(), r.getRequestedBy(), r.getRequestedAt(),
                r.getErrorMessage());
    }
}
