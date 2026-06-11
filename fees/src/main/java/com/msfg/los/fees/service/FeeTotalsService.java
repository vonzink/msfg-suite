package com.msfg.los.fees.service;

import com.msfg.los.fees.domain.FeeSection;
import com.msfg.los.fees.repo.FeeLineItemRepository;
import com.msfg.los.fees.web.dto.FeeTotalsResponse;
import com.msfg.los.fees.web.dto.FeeTotalsResponse.CategoryTotals;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
public class FeeTotalsService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final FeeLineItemRepository fees;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;

    public FeeTotalsService(FeeLineItemRepository fees,
                            LoanService loanService,
                            LoanAccessGuard accessGuard) {
        this.fees = fees;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    @Transactional(readOnly = true)
    public FeeTotalsResponse totals(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        // Seed every section with ZERO so the map is always complete
        Map<String, BigDecimal> sectionTotals = new HashMap<>();
        for (FeeSection s : FeeSection.values()) {
            sectionTotals.put(s.name(), ZERO);
        }

        fees.findByLoanIdOrderByOrdinalAscIdAsc(loanId).forEach(f ->
                sectionTotals.merge(f.getSection().name(), nz(f.getAmount()), BigDecimal::add));

        Function<FeeSection, BigDecimal> S = sec -> sectionTotals.get(sec.name());

        var category = new CategoryTotals(
                S.apply(FeeSection.A),
                S.apply(FeeSection.B),
                S.apply(FeeSection.C),
                S.apply(FeeSection.E),
                S.apply(FeeSection.F).add(S.apply(FeeSection.G)));   // escrowPrepaids = F + G

        return new FeeTotalsResponse(sectionTotals, category);
    }
}
