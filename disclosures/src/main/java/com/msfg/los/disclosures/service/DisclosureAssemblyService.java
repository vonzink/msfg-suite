package com.msfg.los.disclosures.service;

import com.msfg.los.disclosures.domain.CashToCloseRow;
import com.msfg.los.disclosures.domain.DisclosureCostRow;
import com.msfg.los.disclosures.domain.DisclosureKind;
import com.msfg.los.disclosures.domain.DisclosureSnapshot;
import com.msfg.los.disclosures.domain.ToleranceBucket;
import com.msfg.los.disclosures.tolerance.TolerancePolicy;
import com.msfg.los.fees.domain.FeeLineItem;
import com.msfg.los.fees.service.FeeService;
import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.qualification.service.LoanCalculationService;
import com.msfg.los.qualification.web.dto.LoanCalculationResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure assembly of the figures that feed a disclosure: snapshots the loan's fee line items into
 * TRID-bucketed cost rows, pulls loan terms + monthly P&I, and packages a
 * {@link DisclosureGenerationRequest} (vendor input) plus an immutable {@link DisclosureSnapshot}
 * (persisted with the issuance). The caller (issuance service) has already guarded access; calling
 * {@code loanService.get} again here is a cheap tenant-scoped reload, not an access decision.
 *
 * <p>The version passed into the request here is a placeholder (1); the issuance service overwrites
 * it with the real per-(loan,kind) version when it persists.
 */
@Service
public class DisclosureAssemblyService {

    private final LoanService loanService;
    private final FeeService feeService;
    private final TolerancePolicy tolerancePolicy;
    private final LoanCalculationService calculationService;

    public DisclosureAssemblyService(LoanService loanService,
                                     FeeService feeService,
                                     TolerancePolicy tolerancePolicy,
                                     LoanCalculationService calculationService) {
        this.loanService = loanService;
        this.feeService = feeService;
        this.tolerancePolicy = tolerancePolicy;
        this.calculationService = calculationService;
    }

    /** The two products of assembly: vendor request + the snapshot persisted on the issuance. */
    public record AssemblyResult(DisclosureGenerationRequest request, DisclosureSnapshot snapshot) {}

    @Transactional(readOnly = true)
    public AssemblyResult assemble(UUID loanId, DisclosureKind kind) {
        Loan loan = loanService.get(loanId);

        // ── Cost rows from fee line items, each tagged with its TRID tolerance bucket ──
        List<FeeLineItem> fees = feeService.lineItemsForLoan(loanId);
        List<DisclosureCostRow> costRows = new ArrayList<>();
        BigDecimal totalClosingCosts = BigDecimal.ZERO;
        BigDecimal prepaidFinanceCharges = BigDecimal.ZERO;
        for (FeeLineItem fee : fees) {
            String section = fee.getSection() != null ? fee.getSection().name() : null;
            BigDecimal amount = nz(fee.getAmount());
            ToleranceBucket bucket = tolerancePolicy.bucket(
                    section, fee.getPaidTo(), fee.getConsumerCanShop(), fee.getOnWrittenList());
            costRows.add(new DisclosureCostRow(section, fee.getLabel(), fee.getAmount(), bucket));
            totalClosingCosts = totalClosingCosts.add(amount);
            if (bucket == ToleranceBucket.ZERO) {
                prepaidFinanceCharges = prepaidFinanceCharges.add(amount);
            }
        }

        // ── Loan terms ──
        BigDecimal loanAmount = loan.getNoteAmount();
        BigDecimal interestRate = loan.getInterestRate();
        Integer termMonths = loan.getLoanTermMonths();

        // ── Monthly P&I — null-tolerant: calc never throws, P&I field may be null ──
        BigDecimal monthlyPrincipalInterest = null;
        LoanCalculationResponse calc = calculationService.calculate(loanId);
        if (calc != null) {
            monthlyPrincipalInterest = calc.monthlyPrincipalInterest();
        }

        // No loan field carries a prepayment-penalty flag yet.
        boolean prepaymentPenalty = false;

        String productDescription = productDescription(loan);

        List<CashToCloseRow> cashToClose =
                List.of(new CashToCloseRow("Total Closing Costs", totalClosingCosts));

        DisclosureGenerationRequest request = new DisclosureGenerationRequest(
                kind,
                loanId,
                1, // placeholder — issuance service sets the real version
                loan.getLoanNumber(),
                loanAmount,
                interestRate,
                termMonths,
                monthlyPrincipalInterest,
                totalClosingCosts,
                prepaidFinanceCharges,
                prepaymentPenalty,
                productDescription,
                costRows,
                cashToClose);

        DisclosureSnapshot snapshot =
                new DisclosureSnapshot(costRows, cashToClose, loanAmount, interestRate, termMonths);

        return new AssemblyResult(request, snapshot);
    }

    private static String productDescription(Loan loan) {
        String type = loan.getMortgageType() != null ? loan.getMortgageType().name() : null;
        String purpose = loan.getLoanPurpose() != null ? loan.getLoanPurpose().name() : null;
        if (type == null && purpose == null) {
            return null;
        }
        if (type == null) {
            return purpose;
        }
        if (purpose == null) {
            return type;
        }
        return type + " " + purpose;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
