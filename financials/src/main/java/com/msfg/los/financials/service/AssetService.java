package com.msfg.los.financials.service;

import com.msfg.los.financials.domain.Asset;
import com.msfg.los.financials.repo.AssetRepository;
import com.msfg.los.financials.web.dto.AddAssetRequest;
import com.msfg.los.financials.web.dto.UpdateAssetRequest;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.service.BorrowerService;
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
    private final BorrowerService borrowerService;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;

    public AssetService(AssetRepository assets, LoanService loanService,
                        LoanAccessGuard accessGuard, TenantContext tenantContext,
                        BorrowerService borrowerService) {
        this.assets = assets;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
        this.borrowerService = borrowerService;
    }

    private void assertBorrowerInLoan(UUID loanId, UUID borrowerId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        if (!borrowerService.isBorrowerInLoan(loanId, borrowerId))
            throw new NotFoundException("Borrower", borrowerId);
    }

    /**
     * Read gate (T11): staff/owning-LO OR the borrower reading their OWN row. Writes keep the
     * staff-only {@link #assertBorrowerInLoan}.
     */
    private void assertBorrowerSelfReadable(UUID loanId, UUID borrowerId) {
        accessGuard.assertBorrowerSelfReadable(loanService.get(loanId), borrowerId);
        if (!borrowerService.isBorrowerInLoan(loanId, borrowerId))
            throw new NotFoundException("Borrower", borrowerId);
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
        return insert(loanId, borrowerId, req);
    }

    /** Shared construction + validation + ordinal mechanics for {@link #add} and {@link #replaceForBorrowerInternal}. */
    private Asset insert(UUID loanId, UUID borrowerId, AddAssetRequest req) {
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

    /**
     * No-guard self-service write seam (Stage-2 borrower-self application): REPLACE the borrower's
     * entire asset set with {@code items}. Deletes the borrower's existing rows (loaded via
     * {@code findByBorrowerIdOrderByOrdinalAscIdAsc} then {@code deleteAll} so org + RLS scoping
     * holds — never an unscoped bulk delete), then re-adds each item through the SAME
     * {@link #insert} mechanics the public {@link #add} uses (so {@link #validateValue} and the
     * max+1 ordinal logic run per row; ordinals re-derive 0..n-1 from the now-empty set).
     *
     * <p>No-guard; caller authorizes via {@link LoanAccessGuard#assertBorrowerSelfWritable}. The
     * {@code BorrowerApplicationService} orchestrator asserts that gate before invoking this. The
     * public {@link #add}/{@link #update}/{@link #delete} keep their staff {@link #assertBorrowerInLoan}.
     * Runs in one {@code @Transactional} method.
     */
    @Transactional
    public List<Asset> replaceForBorrowerInternal(UUID loanId, UUID borrowerId, List<AddAssetRequest> items) {
        List<Asset> existing = assets.findByBorrowerIdOrderByOrdinalAscIdAsc(borrowerId);
        assets.deleteAll(existing);
        assets.flush();
        List<Asset> created = new java.util.ArrayList<>();
        if (items != null) {
            for (AddAssetRequest req : items) {
                created.add(insert(loanId, borrowerId, req));
            }
        }
        return created;
    }

    @Transactional(readOnly = true)
    public List<Asset> list(UUID loanId, UUID borrowerId) {
        assertBorrowerSelfReadable(loanId, borrowerId);
        return assets.findByBorrowerIdOrderByOrdinalAscIdAsc(borrowerId);
    }

    @Transactional
    public Asset update(UUID loanId, UUID borrowerId, UUID assetId, UpdateAssetRequest req) {
        assertBorrowerInLoan(loanId, borrowerId);
        Asset asset = assets.findByIdAndOrgId(assetId, tenantContext.requireOrgId())
                .filter(x -> x.getBorrowerId().equals(borrowerId))
                .orElseThrow(() -> new NotFoundException("Asset", assetId));

        BigDecimal effectiveValue = req.cashOrMarketValue() != null ? req.cashOrMarketValue() : asset.getCashOrMarketValue();
        validateValue(effectiveValue);

        if (req.assetType() != null) asset.setAssetType(req.assetType());
        if (req.financialInstitution() != null) asset.setFinancialInstitution(req.financialInstitution());
        if (req.accountNumber() != null) asset.setAccountNumber(req.accountNumber());
        if (req.cashOrMarketValue() != null) asset.setCashOrMarketValue(req.cashOrMarketValue());
        if (req.verified() != null) asset.setVerified(req.verified());

        return assets.save(asset);
    }

    @Transactional
    public void delete(UUID loanId, UUID borrowerId, UUID assetId) {
        assertBorrowerInLoan(loanId, borrowerId);
        Asset asset = assets.findByIdAndOrgId(assetId, tenantContext.requireOrgId())
                .filter(x -> x.getBorrowerId().equals(borrowerId))
                .orElseThrow(() -> new NotFoundException("Asset", assetId));
        assets.delete(asset);
    }
}
