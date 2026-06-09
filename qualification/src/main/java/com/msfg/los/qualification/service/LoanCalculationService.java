package com.msfg.los.qualification.service;

import com.msfg.los.financials.service.LiabilitySummaryService;
import com.msfg.los.income.service.IncomeSummaryService;
import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.domain.LoanPurposeType;
import com.msfg.los.loan.domain.SubjectProperty;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.qualification.math.MortgageMath;
import com.msfg.los.qualification.web.dto.LoanCalculationResponse;
import com.msfg.los.reo.domain.RealEstateOwned;
import com.msfg.los.reo.repo.RealEstateOwnedRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.msfg.los.qualification.math.MortgageMath.money;
import static com.msfg.los.qualification.math.MortgageMath.nz;
import static com.msfg.los.qualification.math.MortgageMath.percentRatio;

/**
 * Read-only qualification formula engine.
 *
 * <p>All derived figures follow the normative formulas in Spec 6B. Missing inputs return null
 * for the dependent figures — never throws due to missing data or divide-by-zero.
 */
@Service
public class LoanCalculationService {

    private static final BigDecimal SEVENTY_FIVE_PCT = new BigDecimal("0.75");

    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final IncomeSummaryService incomeSummary;
    private final LiabilitySummaryService liabilitySummary;
    private final RealEstateOwnedRepository reoRepo;

    public LoanCalculationService(
            LoanService loanService,
            LoanAccessGuard accessGuard,
            IncomeSummaryService incomeSummary,
            LiabilitySummaryService liabilitySummary,
            RealEstateOwnedRepository reoRepo) {
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.incomeSummary = incomeSummary;
        this.liabilitySummary = liabilitySummary;
        this.reoRepo = reoRepo;
    }

    @Transactional(readOnly = true)
    public LoanCalculationResponse calculate(UUID loanId) {

        // ── 1. Guard: 404 if cross-org, 401 if unauthenticated ────────────────
        Loan loan = loanService.get(loanId);
        accessGuard.assertCanAccess(loan);

        // ── 2. Pull inputs ────────────────────────────────────────────────────
        BigDecimal base            = loan.getBaseLoanAmount();
        BigDecimal second          = loan.getSecondLoanAmount();
        BigDecimal financedFees    = loan.getFinancedFeesAmount();
        BigDecimal rate            = loan.getInterestRate();
        Integer    termMonths      = loan.getLoanTermMonths();
        LoanPurposeType purpose    = loan.getLoanPurpose();

        BigDecimal taxes   = loan.getProposedTaxesMonthly();
        BigDecimal hazIns  = loan.getProposedHazardInsuranceMonthly();
        BigDecimal hoa     = loan.getProposedHoaDuesMonthly();
        BigDecimal mi      = loan.getProposedMortgageInsuranceMonthly();

        SubjectProperty prop = loan.getSubjectProperty();
        BigDecimal salesPrice      = prop != null ? prop.getSalesPrice()      : null;
        BigDecimal appraised       = prop != null ? prop.getAppraisedValue()  : null;
        BigDecimal estimated       = prop != null ? prop.getEstimatedValue()  : null;

        // ── 3. Total loan amount ──────────────────────────────────────────────
        // Null when base is null (not computable).
        BigDecimal totalLoanAmount = (base == null) ? null
                : money(nz(base).add(nz(financedFees)));

        // ── 4. LTV basis ──────────────────────────────────────────────────────
        BigDecimal ltvBasis = computeLtvBasis(purpose, salesPrice, appraised, estimated);

        // ── 5. LTV / CLTV / TLTV — percentRatio returns null if basis null/zero
        BigDecimal ltv  = percentRatio(base, ltvBasis);
        BigDecimal cltv = (base == null) ? null : percentRatio(base.add(nz(second)), ltvBasis);
        // Per spec: tltv = cltv (no separate HELOC-line field yet)
        BigDecimal tltv = cltv;

        // ── 6. Monthly P&I ────────────────────────────────────────────────────
        BigDecimal pi = computeMonthlyPI(base, rate, termMonths);

        // ── 7. Proposed housing (PITI) — null if P&I null ─────────────────────
        BigDecimal proposedHousing = (pi == null) ? null
                : money(pi.add(nz(taxes)).add(nz(hazIns)).add(nz(hoa)).add(nz(mi)));

        // ── 8. Net rental income / debt (Fannie 75% convention) ───────────────
        List<RealEstateOwned> reoRows = reoRepo.findByLoanIdOrderByOrdinalAsc(loanId);
        BigDecimal netRentalIncome = BigDecimal.ZERO;
        BigDecimal netRentalDebt   = BigDecimal.ZERO;
        for (RealEstateOwned reo : reoRows) {
            BigDecimal net = SEVENTY_FIVE_PCT.multiply(nz(reo.getGrossMonthlyRentalIncome()))
                    .subtract(nz(reo.getMortgageMonthlyPayment()));
            netRentalIncome = netRentalIncome.add(net.max(BigDecimal.ZERO));
            netRentalDebt   = netRentalDebt.add(net.negate().max(BigDecimal.ZERO));
        }
        netRentalIncome = money(netRentalIncome);
        netRentalDebt   = money(netRentalDebt);

        // ── 9. Income ─────────────────────────────────────────────────────────
        // IncomeSummaryService is ZERO-seeded (never null).
        BigDecimal baseIncome = incomeSummary.summarize(loanId).totalMonthlyIncome();
        BigDecimal totalIncome = money(nz(baseIncome).add(nz(netRentalIncome)));

        // ── 10. Debts ─────────────────────────────────────────────────────────
        BigDecimal dtiLiabilities  = liabilitySummary.summarize(loanId).dtiMonthlyPayments();
        BigDecimal totalDebts = money(nz(dtiLiabilities).add(nz(netRentalDebt)));

        // ── 11. DTI ───────────────────────────────────────────────────────────
        // frontEndDti: null if proposedHousing null or totalIncome zero
        BigDecimal frontEndDti = percentRatio(proposedHousing, totalIncome);

        // backEndDti: null if proposedHousing null (housing is the mandatory component)
        BigDecimal backEndDti = (proposedHousing == null) ? null
                : percentRatio(proposedHousing.add(nz(totalDebts)), totalIncome);

        // ── 12. Present housing (best-effort — null if unavailable) ───────────
        // Per spec: primary borrower's present-address rentAmount (S3 BorrowerAddress).
        // Deferred/null — no BorrowerAddressRepository injected; spec marks this best-effort.
        BigDecimal presentHousing = null;
        BigDecimal housingDelta   = (proposedHousing != null && presentHousing != null)
                ? money(proposedHousing.subtract(presentHousing))
                : null;

        // ── 13. Build response ────────────────────────────────────────────────
        return new LoanCalculationResponse(
                base,
                second,
                financedFees,
                rate,
                termMonths,
                purpose != null ? purpose.name() : null,
                totalLoanAmount,
                ltvBasis,
                ltv,
                cltv,
                tltv,
                pi,
                proposedHousing,
                presentHousing,
                housingDelta,
                baseIncome,
                netRentalIncome,
                totalIncome,
                dtiLiabilities,
                netRentalDebt,
                totalDebts,
                frontEndDti,
                backEndDti
        );
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * LTV basis per spec §2:
     * PURCHASE  → lesser of salesPrice/appraisedValue; if one is null use the other; both null → null.
     * non-PURCHASE → appraisedValue; fallback estimatedValue; else null.
     */
    private static BigDecimal computeLtvBasis(
            LoanPurposeType purpose,
            BigDecimal salesPrice,
            BigDecimal appraised,
            BigDecimal estimated) {

        if (purpose == LoanPurposeType.PURCHASE) {
            if (salesPrice == null && appraised == null) return null;
            if (salesPrice == null) return appraised;
            if (appraised == null) return salesPrice;
            return salesPrice.min(appraised);
        } else {
            // REFINANCE / CONSTRUCTION / OTHER / null purpose
            if (appraised != null) return appraised;
            return estimated; // null if both absent
        }
    }

    /**
     * Monthly P&I per spec §4: null if base/rate/term null or term ≤ 0.
     * Delegates to MortgageMath (which handles zero-rate via base/term).
     */
    private static BigDecimal computeMonthlyPI(BigDecimal base, BigDecimal rate, Integer term) {
        if (base == null || rate == null || term == null || term <= 0) return null;
        return MortgageMath.monthlyPrincipalInterest(base, rate, term);
    }
}
