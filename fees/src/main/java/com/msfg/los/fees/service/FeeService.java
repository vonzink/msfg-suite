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

    @Transactional
    public FeeLineItem add(UUID loanId, AddFeeRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        if (req.amount() != null && req.amount().signum() < 0)
            throw new ValidationException("amount must be >= 0");
        if (req.sellerConcession() != null && req.sellerConcession().signum() < 0)
            throw new ValidationException("sellerConcession must be >= 0");
        if (req.percent() != null && req.percent().signum() < 0)
            throw new ValidationException("percent must be >= 0");

        if (fees.existsByLoanIdAndSectionAndLabel(loanId, req.section(), req.label()))
            throw new ConflictException("Fee already exists for section " + req.section() + " / " + req.label());

        FeeLineItem f = new FeeLineItem();
        f.setLoanId(loanId);
        f.setSection(req.section());
        f.setLabel(req.label());
        f.setAmount(req.amount());
        f.setSellerConcession(req.sellerConcession());
        f.setPercent(req.percent());
        f.setOrdinal((int) fees.countByLoanId(loanId));
        return fees.save(f);
    }

    @Transactional(readOnly = true)
    public List<FeeLineItem> list(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return fees.findByLoanIdOrderByOrdinalAsc(loanId);
    }

    @Transactional
    public FeeLineItem update(UUID loanId, UUID feeId, UpdateFeeRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        FeeLineItem f = load(loanId, feeId);

        if (req.amount() != null) {
            if (req.amount().signum() < 0)
                throw new ValidationException("amount must be >= 0");
            f.setAmount(req.amount());
        }
        if (req.sellerConcession() != null) {
            if (req.sellerConcession().signum() < 0)
                throw new ValidationException("sellerConcession must be >= 0");
            f.setSellerConcession(req.sellerConcession());
        }
        if (req.percent() != null) {
            if (req.percent().signum() < 0)
                throw new ValidationException("percent must be >= 0");
            f.setPercent(req.percent());
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
