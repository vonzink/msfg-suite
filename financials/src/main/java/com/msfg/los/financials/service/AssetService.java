package com.msfg.los.financials.service;

import com.msfg.los.financials.domain.Asset;
import com.msfg.los.financials.repo.AssetRepository;
import com.msfg.los.financials.web.dto.AddAssetRequest;
import com.msfg.los.financials.web.dto.UpdateAssetRequest;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.repo.BorrowerRepository;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class AssetService {

    private final AssetRepository assets;
    private final BorrowerRepository borrowers;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;

    public AssetService(AssetRepository assets, LoanService loanService,
                        LoanAccessGuard accessGuard, TenantContext tenantContext,
                        BorrowerRepository borrowers) {
        this.assets = assets;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
        this.borrowers = borrowers;
    }

    private UUID org() {
        return tenantContext.orgId().orElseThrow(() -> new NotFoundException("Tenant", "current"));
    }

    private void assertBorrowerInLoan(UUID loanId, UUID borrowerId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        borrowers.findByIdAndOrgId(borrowerId, org())
                .filter(b -> b.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("Borrower", borrowerId));
    }

    // max+1, not count — count reuses ordinals after a delete and collides
    private int nextOrdinal(UUID borrowerId) {
        return assets.findTopByBorrowerIdOrderByOrdinalDesc(borrowerId)
                .map(x -> x.getOrdinal() + 1)
                .orElse(0);
    }

    private void validateValue(BigDecimal cashOrMarketValue) {
        if (cashOrMarketValue != null && cashOrMarketValue.signum() < 0)
            throw new ValidationException("cashOrMarketValue must be >= 0");
    }

    @Transactional
    public Asset add(UUID loanId, UUID borrowerId, AddAssetRequest req) {
        assertBorrowerInLoan(loanId, borrowerId);
        validateValue(req.cashOrMarketValue());
        Asset asset = new Asset();
        asset.setLoanId(loanId);
        asset.setBorrowerId(borrowerId);
        asset.setAssetType(req.assetType());
        asset.setFinancialInstitution(req.financialInstitution());
        asset.setAccountNumber(req.accountNumber());
        asset.setCashOrMarketValue(req.cashOrMarketValue());
        asset.setVerified(req.verified());
        asset.setOrdinal(nextOrdinal(borrowerId));
        return assets.save(asset);
    }

    @Transactional(readOnly = true)
    public List<Asset> list(UUID loanId, UUID borrowerId) {
        assertBorrowerInLoan(loanId, borrowerId);
        return assets.findByBorrowerIdOrderByOrdinalAscIdAsc(borrowerId);
    }

    @Transactional
    public Asset update(UUID loanId, UUID borrowerId, UUID assetId, UpdateAssetRequest req) {
        assertBorrowerInLoan(loanId, borrowerId);
        Asset asset = assets.findByIdAndOrgId(assetId, org())
                .filter(x -> x.getBorrowerId().equals(borrowerId))
                .orElseThrow(() -> new NotFoundException("Asset", assetId));

        BigDecimal effectiveValue = req.cashOrMarketValue() != null ? req.cashOrMarketValue() : asset.getCashOrMarketValue();
        validateValue(effectiveValue);

        if (req.assetType() != null) asset.setAssetType(req.assetType());
        if (req.financialInstitution() != null) asset.setFinancialInstitution(req.financialInstitution());
        if (req.accountNumber() != null) asset.setAccountNumber(req.accountNumber());
        if (req.cashOrMarketValue() != null) asset.setCashOrMarketValue(req.cashOrMarketValue());
        if (req.verified() != null) asset.setVerified(req.verified());

        return asset;
    }

    @Transactional
    public void delete(UUID loanId, UUID borrowerId, UUID assetId) {
        assertBorrowerInLoan(loanId, borrowerId);
        Asset asset = assets.findByIdAndOrgId(assetId, org())
                .filter(x -> x.getBorrowerId().equals(borrowerId))
                .orElseThrow(() -> new NotFoundException("Asset", assetId));
        assets.delete(asset);
    }
}
