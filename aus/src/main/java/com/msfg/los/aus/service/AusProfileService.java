package com.msfg.los.aus.service;

import com.msfg.los.aus.domain.AusProfile;
import com.msfg.los.aus.domain.AusVendorSettings;
import com.msfg.los.aus.domain.CredentialVendor;
import com.msfg.los.aus.domain.CreditReference;
import com.msfg.los.aus.repo.AusProfileRepository;
import com.msfg.los.aus.web.dto.AusProfileResponse;
import com.msfg.los.aus.web.dto.AusVendorSettingsView;
import com.msfg.los.aus.web.dto.UpsertAusProfileRequest;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.domain.BorrowerParty;
import com.msfg.los.parties.service.BorrowerService;
import com.msfg.los.platform.error.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Per-loan AUS profile (1:1, GET-empty/PUT-upsert — the CoC-draft pattern). Each vendor view also
 * reports its current {@code credentialSource} via {@link VendorCredentialService#resolve} (only
 * the source enum — never the resolved secrets).
 */
@Service
public class AusProfileService {

    private final AusProfileRepository profiles;
    private final VendorCredentialService credentials;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final BorrowerService borrowerService;

    public AusProfileService(AusProfileRepository profiles,
                             VendorCredentialService credentials,
                             LoanService loanService,
                             LoanAccessGuard accessGuard,
                             BorrowerService borrowerService) {
        this.profiles = profiles;
        this.credentials = credentials;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.borrowerService = borrowerService;
    }

    private void guard(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
    }

    @Transactional(readOnly = true)
    public AusProfileResponse get(UUID loanId) {
        guard(loanId);
        return toResponse(loanId, profiles.findByLoanId(loanId).orElse(null));
    }

    /** Null {@code du}/{@code lpa} in the request = leave that vendor's settings unchanged. */
    @Transactional
    public AusProfileResponse upsert(UUID loanId, UpsertAusProfileRequest req) {
        guard(loanId);
        AusProfile profile = profiles.findByLoanId(loanId).orElseGet(() -> {
            AusProfile p = new AusProfile();
            p.setLoanId(loanId);
            return p;
        });
        if (req.du() != null) profile.setDuSettings(normalize(loanId, req.du()));
        if (req.lpa() != null) profile.setLpaSettings(normalize(loanId, req.lpa()));
        return toResponse(loanId, profiles.save(profile));
    }

    /**
     * Never store null creditReferences (coerced to an empty list), and every referenced
     * borrower must be a member of this loan.
     */
    private AusVendorSettings normalize(UUID loanId, AusVendorSettings s) {
        List<CreditReference> refs = s.creditReferences() == null ? List.of() : s.creditReferences();
        if (!refs.isEmpty()) {
            Set<UUID> loanBorrowerIds = borrowerService.listByLoan(loanId).stream()
                    .map(BorrowerParty::getId)
                    .collect(Collectors.toSet());
            for (CreditReference ref : refs) {
                if (ref == null || ref.borrowerId() == null || !loanBorrowerIds.contains(ref.borrowerId())) {
                    throw new ValidationException(
                            "creditReferences: unknown borrower " + (ref == null ? null : ref.borrowerId()));
                }
            }
            refs = List.copyOf(refs);
        }
        return new AusVendorSettings(s.issueMode(), s.creditProviderCode(), s.fhaCaseNumber(),
                s.branchNumber(), refs);
    }

    private AusProfileResponse toResponse(UUID loanId, AusProfile profile) {
        return new AusProfileResponse(
                toView(loanId, profile == null ? null : profile.getDuSettings(), CredentialVendor.DU),
                toView(loanId, profile == null ? null : profile.getLpaSettings(), CredentialVendor.LPA));
    }

    private AusVendorSettingsView toView(UUID loanId, AusVendorSettings s, CredentialVendor vendor) {
        var source = credentials.resolve(loanId, vendor).source();
        if (s == null) {
            return new AusVendorSettingsView(null, null, null, null, List.of(), source);
        }
        List<CreditReference> refs = s.creditReferences() == null ? List.of() : s.creditReferences();
        return new AusVendorSettingsView(s.issueMode(), s.creditProviderCode(), s.fhaCaseNumber(),
                s.branchNumber(), refs, source);
    }
}
