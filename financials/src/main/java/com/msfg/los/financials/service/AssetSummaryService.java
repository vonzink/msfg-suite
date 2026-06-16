package com.msfg.los.financials.service;

import com.msfg.los.financials.domain.Asset;
import com.msfg.los.financials.repo.AssetRepository;
import com.msfg.los.financials.web.dto.AssetSummaryResponse;
import com.msfg.los.financials.web.dto.AssetSummaryRow;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.domain.BorrowerParty;
import com.msfg.los.parties.service.BorrowerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AssetSummaryService {

    private final AssetRepository assets;
    private final BorrowerService borrowerService;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;

    public AssetSummaryService(AssetRepository assets, BorrowerService borrowerService,
                               LoanService loanService, LoanAccessGuard accessGuard) {
        this.assets = assets;
        this.borrowerService = borrowerService;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
    }

    @Transactional(readOnly = true)
    public AssetSummaryResponse summarize(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        Map<UUID, String> borrowerNames = borrowerService.listByLoan(loanId).stream()
                .collect(Collectors.toMap(BorrowerParty::getId, AssetSummaryService::fullName));

        List<Asset> items = assets.findByLoanIdOrderByOrdinalAsc(loanId);
        List<AssetSummaryRow> rows = items.stream().map(a -> new AssetSummaryRow(
                a.getBorrowerId(),
                borrowerNames.get(a.getBorrowerId()),
                a.getAssetType(),
                a.getFinancialInstitution(),
                a.getCashOrMarketValue()
        )).toList();

        BigDecimal totalAssets = items.stream()
                .map(Asset::getCashOrMarketValue)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new AssetSummaryResponse(rows, totalAssets);
    }

    private static String fullName(BorrowerParty b) {
        String first = b.getFirstName() == null ? "" : b.getFirstName();
        String last = b.getLastName() == null ? "" : b.getLastName();
        return (first + " " + last).trim();
    }
}
