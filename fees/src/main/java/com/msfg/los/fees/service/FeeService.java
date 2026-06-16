package com.msfg.los.fees.service;

import com.msfg.los.fees.domain.FeeLineItem;
import com.msfg.los.fees.repo.FeeLineItemRepository;
import com.msfg.los.fees.web.dto.AddFeeRequest;
import com.msfg.los.fees.web.dto.UpdateFeeRequest;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.platform.error.ConflictException;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class FeeService {

    private final FeeLineItemRepository fees;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;

    public FeeService(FeeLineItemRepository fees,
                      LoanService loanService,
                      LoanAccessGuard accessGuard,
                      TenantContext tenantContext) {
        this.fees = fees;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
    }

    private UUID org() {
        return tenantContext.orgId().orElseThrow(() -> new NotFoundException("Tenant", "current"));
    }

    private FeeLineItem load(UUID loanId, UUID feeId) {
        return fees.findByIdAndOrgId(feeId, org())
                .filter(f -> f.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("FeeLineItem", feeId));
    }

    // amount/sellerConcession may be negative: PRORATIONS and section-L rows are credits.
    private void requireNonNegativePercent(BigDecimal percent) {
        if (percent != null && percent.signum() < 0)
            throw new ValidationException("percent must be >= 0");
    }

    // max+1, not count: count reuses ordinals after a delete.
    private int nextOrdinal(UUID loanId) {
        return fees.findTopByLoanIdOrderByOrdinalDesc(loanId)
                .map(f -> f.getOrdinal() + 1)
                .orElse(0);
    }

    @Transactional
    public FeeLineItem add(UUID loanId, AddFeeRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        requireNonNegativePercent(req.percent());

        if (fees.existsByLoanIdAndSectionAndLabel(loanId, req.section(), req.label()))
            throw new ConflictException("Fee already exists for section " + req.section() + " / " + req.label());

        FeeLineItem f = new FeeLineItem();
        f.setLoanId(loanId);
        f.setSection(req.section());
        f.setLabel(req.label());
        f.setAmount(req.amount());
        f.setSellerConcession(req.sellerConcession());
        f.setPercent(req.percent());
        f.setPaidTo(req.paidTo());
        f.setConsumerCanShop(req.consumerCanShop());
        f.setOnWrittenList(req.onWrittenList());
        f.setOrdinal(nextOrdinal(loanId));
        return fees.save(f);
    }

    /**
     * Create-or-update keyed by (section,label) — the frontend's Record key.
     * PUT semantics: amount/sellerConcession/percent are replaced wholesale;
     * ordinal is assigned on create and stable on update.
     */
    @Transactional
    public FeeLineItem upsert(UUID loanId, AddFeeRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        requireNonNegativePercent(req.percent());

        FeeLineItem f = fees.findByLoanIdAndSectionAndLabel(loanId, req.section(), req.label())
                .orElseGet(() -> {
                    FeeLineItem n = new FeeLineItem();
                    n.setLoanId(loanId);
                    n.setSection(req.section());
                    n.setLabel(req.label());
                    n.setOrdinal(nextOrdinal(loanId));
                    return n;
                });
        f.setAmount(req.amount());
        f.setSellerConcession(req.sellerConcession());
        f.setPercent(req.percent());
        f.setPaidTo(req.paidTo());
        f.setConsumerCanShop(req.consumerCanShop());
        f.setOnWrittenList(req.onWrittenList());
        return fees.save(f);
    }

    @Transactional(readOnly = true)
    public List<FeeLineItem> list(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return fees.findByLoanIdOrderByOrdinalAscIdAsc(loanId);
    }

    /**
     * Cross-module read seam: the loan's fee line items, tenant-scoped and ordinal-ordered, WITHOUT
     * a loan access decision — the disclosure-assembly caller has already guarded access (its reload
     * here is a tenant-scoped reload, not an access decision). Mirrors the raw
     * {@code findByLoanIdOrderByOrdinalAscIdAsc} query.
     */
    @Transactional(readOnly = true)
    public List<FeeLineItem> lineItemsForLoan(UUID loanId) {
        return fees.findByLoanIdOrderByOrdinalAscIdAsc(loanId);
    }

    @Transactional
    public FeeLineItem update(UUID loanId, UUID feeId, UpdateFeeRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        FeeLineItem f = load(loanId, feeId);

        if (req.amount() != null) {
            f.setAmount(req.amount());
        }
        if (req.sellerConcession() != null) {
            f.setSellerConcession(req.sellerConcession());
        }
        if (req.percent() != null) {
            requireNonNegativePercent(req.percent());
            f.setPercent(req.percent());
        }
        if (req.paidTo() != null) {
            f.setPaidTo(req.paidTo());
        }
        if (req.consumerCanShop() != null) {
            f.setConsumerCanShop(req.consumerCanShop());
        }
        if (req.onWrittenList() != null) {
            f.setOnWrittenList(req.onWrittenList());
        }
        return fees.save(f);
    }

    @Transactional
    public void delete(UUID loanId, UUID feeId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        FeeLineItem f = load(loanId, feeId);
        fees.delete(f);
    }
}
