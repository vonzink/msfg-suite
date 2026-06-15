package com.msfg.los.disclosures.service;

import com.msfg.los.disclosures.web.dto.CoverageResponse;
import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.domain.PropertyType;
import com.msfg.los.loan.domain.SubjectProperty;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TRID coverage gate — decides whether a loan is subject to the Integrated Disclosure
 * regime (Loan Estimate / Closing Disclosure) under 12 CFR 1026.19(e)/(f), which applies
 * to most <b>closed-end consumer credit transactions secured by real property</b>
 * (Regulation Z, 12 CFR 1026.19).
 *
 * <h2>The 5-part TRID gate vs. what this loan model exposes (v1)</h2>
 * <ol>
 *   <li><b>Consumer credit</b> (1026.2(a)(12) — primarily personal/family/household purpose,
 *       not business/commercial/agricultural) — <i>ASSUMED.</i> The loan model carries no
 *       business-purpose indicator; an LOS originating residential mortgages is consumer by
 *       construction. No positive out-of-scope signal exists to flip this.</li>
 *   <li><b>Closed-end</b> (not open-end/HELOC per 1026.40) — <i>ASSUMED.</i> No open-end /
 *       HELOC value exists on any loan enum (loanPurpose, mortgageType, amortizationType,
 *       lienPriority). Nothing to gate on, so treated as closed-end.</li>
 *   <li><b>Secured by real property</b> (or a cooperative unit — expressly pulled in by
 *       1026.19(e)/(f) and the 2017 amendments) — <i>PARTIALLY EVALUABLE.</i> Every loan in
 *       this LOS is mortgage-secured; subjectProperty.propertyType is informational only.
 *       <b>Co-ops MUST be covered</b> even though a cooperative unit is personal property, so
 *       we deliberately do NOT apply a real-property-only check that would exclude
 *       {@link PropertyType#COOPERATIVE}.</li>
 *   <li><b>Not an expressly exempt product</b> — reverse mortgages (1026.33), and certain
 *       other carve-outs — <i>ASSUMED out-of-scope-free.</i> No reverse-mortgage value exists
 *       on any loan enum, so no exemption can be positively detected.</li>
 *   <li><b>Not otherwise excluded</b> (e.g. 1026.3 partial exemptions) — <i>ASSUMED.</i> No
 *       signal in the model.</li>
 * </ol>
 *
 * <p><b>v1 rule:</b> a closed-end consumer mortgage secured by real property OR a co-op is
 * COVERED. We return {@code covered=false} ONLY when the loan model gives a clear out-of-scope
 * signal (a HELOC / reverse / open-end / business-purpose enum value). The model exposes no
 * such value today, so this gate returns {@code covered=true} for every loan, annotated with a
 * v1-assumption reason. When real out-of-scope signals are added to the loan model, branch on
 * them here and set {@code covered=false} with a criterion-specific reason.</p>
 */
@Service
public class CoverageService {

    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;

    public CoverageService(LoanService loanService, LoanAccessGuard accessGuard) {
        this.loanService = loanService;
        this.accessGuard = accessGuard;
    }

    @Transactional(readOnly = true)
    public CoverageResponse evaluate(UUID loanId) {
        // Guard FIRST: 404 cross-tenant / 403 by role before any field is read.
        Loan loan = loanService.get(loanId);
        accessGuard.assertCanAccess(loan);

        List<String> reasons = new ArrayList<>();

        // Out-of-scope detection: the loan model exposes NO HELOC / reverse / open-end /
        // business-purpose enum value on any field, so there is nothing to positively flip
        // coverage off. If such a signal is added later, branch here and return covered=false.

        reasons.add("Closed-end consumer mortgage — TRID (12 CFR 1026.19) applies");

        // Criterion 3 note: co-ops are covered too — do NOT exclude on personal-property grounds.
        SubjectProperty sp = loan.getSubjectProperty();
        if (sp != null && sp.getPropertyType() == PropertyType.COOPERATIVE) {
            reasons.add("Cooperative unit — covered by TRID (1026.19(e)/(f)) despite personal-property status");
        }

        reasons.add("v1: no out-of-scope signal in loan model — assumed covered");

        return new CoverageResponse(true, reasons);
    }
}
