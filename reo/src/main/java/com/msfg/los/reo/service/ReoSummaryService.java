package com.msfg.los.reo.service;

import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.reo.domain.RealEstateOwned;
import com.msfg.los.reo.repo.RealEstateOwnedRepository;
import com.msfg.los.reo.web.dto.ReoSummaryResponse;
import com.msfg.los.reo.web.dto.ReoSummaryRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class ReoSummaryService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final RealEstateOwnedRepository reo;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;

    public ReoSummaryService(RealEstateOwnedRepository reo,
                             LoanService loanService,
                             LoanAccessGuard accessGuard) {
        this.reo = reo;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    @Transactional(readOnly = true)
    public ReoSummaryResponse summarize(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        List<RealEstateOwned> items = reo.findByLoanIdOrderByOrdinalAsc(loanId);

        List<ReoSummaryRow> rows = items.stream().map(r -> new ReoSummaryRow(
                r.getId(),
                r.isSubjectProperty(),
                r.getPropertyType(),
                r.getPropertyStatus(),
                r.getMarketValue(),
                r.getGrossMonthlyRentalIncome()
        )).toList();

        BigDecimal totalMarketValue = items.stream()
                .map(RealEstateOwned::getMarketValue)
                .filter(v -> v != null)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal totalGrossMonthlyRentalIncome = items.stream()
                .map(RealEstateOwned::getGrossMonthlyRentalIncome)
                .filter(v -> v != null)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal totalMonthlyExpenses = items.stream()
                .map(r -> nz(r.getMonthlyTaxes())
                        .add(nz(r.getMonthlyInsurance()))
                        .add(nz(r.getMonthlyHoaDues()))
                        .add(nz(r.getMonthlyMaintenance())))
                .reduce(ZERO, BigDecimal::add);

        BigDecimal totalMortgageUnpaidBalance = items.stream()
                .map(RealEstateOwned::getMortgageUnpaidBalance)
                .filter(v -> v != null)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal totalMonthlyMortgagePayment = items.stream()
                .map(RealEstateOwned::getMortgageMonthlyPayment)
                .filter(v -> v != null)
                .reduce(ZERO, BigDecimal::add);

        return new ReoSummaryResponse(
                rows,
                totalMarketValue,
                totalGrossMonthlyRentalIncome,
                totalMonthlyExpenses,
                totalMortgageUnpaidBalance,
                totalMonthlyMortgagePayment
        );
    }
}
