package com.msfg.los.financials.service;

import com.msfg.los.financials.domain.Liability;
import com.msfg.los.financials.repo.LiabilityRepository;
import com.msfg.los.financials.web.dto.LiabilitySummaryResponse;
import com.msfg.los.financials.web.dto.LiabilitySummaryRow;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.domain.BorrowerParty;
import com.msfg.los.parties.repo.BorrowerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class LiabilitySummaryService {

    private final LiabilityRepository liabilities;
    private final BorrowerRepository borrowers;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;

    public LiabilitySummaryService(LiabilityRepository liabilities, BorrowerRepository borrowers,
                                   LoanService loanService, LoanAccessGuard accessGuard) {
        this.liabilities = liabilities;
        this.borrowers = borrowers;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
    }

    @Transactional(readOnly = true)
    public LiabilitySummaryResponse summarize(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        Map<UUID, String> borrowerNames = borrowers.findByLoanIdOrderByOrdinalAsc(loanId).stream()
                .collect(Collectors.toMap(BorrowerParty::getId, LiabilitySummaryService::fullName));

        List<Liability> items = liabilities.findByLoanIdOrderByOrdinalAsc(loanId);
        List<LiabilitySummaryRow> rows = items.stream().map(l -> new LiabilitySummaryRow(
                l.getBorrowerId(),
                borrowerNames.get(l.getBorrowerId()),
                l.getLiabilityType(),
                l.getCreditorName(),
                l.getMonthlyPayment(),
                l.isIncludeInDti()
        )).toList();

        BigDecimal totalMonthlyPayments = items.stream()
                .map(Liability::getMonthlyPayment)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal dtiMonthlyPayments = items.stream()
                .filter(Liability::isIncludeInDti)
                .map(Liability::getMonthlyPayment)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalUnpaidBalance = items.stream()
                .map(Liability::getUnpaidBalance)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new LiabilitySummaryResponse(rows, totalMonthlyPayments, dtiMonthlyPayments, totalUnpaidBalance);
    }

    private static String fullName(BorrowerParty b) {
        String first = b.getFirstName() == null ? "" : b.getFirstName();
        String last = b.getLastName() == null ? "" : b.getLastName();
        return (first + " " + last).trim();
    }
}
