package com.msfg.los.conditions.service;

import com.msfg.los.conditions.domain.ConditionStatus;
import com.msfg.los.conditions.domain.LoanCondition;
import com.msfg.los.conditions.repo.LoanConditionRepository;
import com.msfg.los.conditions.web.dto.UpsertConditionRequest;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Loan-scoped underwriting-condition CRUD. Every write/read first asserts the caller can access the
 * loan via {@link LoanAccessGuard} (staff-only, loan-access-gated — no role gate beyond loan access,
 * matching mortgage-app). Cross-module loan reads go through {@link LoanService} (ArchUnit forbids
 * importing another module's repository).
 */
@Service
public class ConditionService {

    private final LoanConditionRepository conditions;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;
    private final CurrentUser currentUser;

    public ConditionService(LoanConditionRepository conditions,
                            LoanService loanService,
                            LoanAccessGuard accessGuard,
                            TenantContext tenantContext,
                            CurrentUser currentUser) {
        this.conditions = conditions;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
        this.currentUser = currentUser;
    }

    /** Tenant-scoped load + same-loan + not-soft-deleted guard. */
    private LoanCondition load(UUID loanId, UUID conditionId) {
        return conditions.findByIdAndOrgId(conditionId, tenantContext.requireOrgId())
                .filter(c -> c.getLoanId().equals(loanId) && c.getDeletedAt() == null)
                .orElseThrow(() -> new NotFoundException("Condition", conditionId));
    }

    @Transactional
    public LoanCondition create(UUID loanId, UpsertConditionRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        if (req.conditionText() == null || req.conditionText().isBlank())
            throw new ValidationException("conditionText must not be blank");

        LoanCondition c = new LoanCondition();
        c.setLoanId(loanId);
        c.setConditionText(req.conditionText());
        c.setConditionType(req.conditionType());
        c.setStatus(req.status() != null ? req.status() : ConditionStatus.Outstanding);
        c.setAssignedTo(req.assignedTo());
        c.setDueDate(req.dueDate());
        c.setNotes(req.notes());

        // If the caller creates a condition already in a cleared/waived state, stamp who/when.
        if (c.getStatus() != ConditionStatus.Outstanding) {
            c.setClearedAt(Instant.now());
            c.setClearedBy(currentUser.id().orElse(null));
        }
        return conditions.save(c);
    }

    @Transactional(readOnly = true)
    public List<LoanCondition> list(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return conditions.findByLoanIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(loanId);
    }

    @Transactional
    public LoanCondition update(UUID loanId, UUID conditionId, UpsertConditionRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        LoanCondition c = load(loanId, conditionId);

        if (req.conditionText() != null) {
            if (req.conditionText().isBlank())
                throw new ValidationException("conditionText must not be blank");
            c.setConditionText(req.conditionText());
        }
        if (req.conditionType() != null) c.setConditionType(req.conditionType());
        if (req.assignedTo() != null) c.setAssignedTo(req.assignedTo());
        if (req.dueDate() != null) c.setDueDate(req.dueDate());
        if (req.notes() != null) c.setNotes(req.notes());

        // --- clear / reopen logic ---
        if (req.status() != null && req.status() != c.getStatus()) {
            ConditionStatus from = c.getStatus();
            ConditionStatus to = req.status();
            c.setStatus(to);

            boolean toResolved = to == ConditionStatus.Cleared || to == ConditionStatus.Waived;
            boolean fromOutstanding = from == ConditionStatus.Outstanding;

            if (fromOutstanding && toResolved) {
                // Outstanding → Cleared|Waived: stamp who/when cleared.
                c.setClearedAt(Instant.now());
                c.setClearedBy(currentUser.id().orElse(null));
            } else if (to == ConditionStatus.Outstanding) {
                // reopened → Outstanding: wipe the clear stamp.
                c.setClearedAt(null);
                c.setClearedBy(null);
            }
        }
        return conditions.save(c);
    }

    @Transactional
    public void softDelete(UUID loanId, UUID conditionId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        LoanCondition c = load(loanId, conditionId);
        c.setDeletedAt(Instant.now());
        conditions.save(c);
    }

    /**
     * Count of OUTSTANDING (non-soft-deleted) conditions for a loan. Public cross-module read seam for
     * the pipeline {@code conditionsGt} filter (T4) and the dashboard (T6). No access decision here —
     * those callers have already authorized the loan.
     */
    @Transactional(readOnly = true)
    public int outstandingCount(UUID loanId) {
        return conditions.countByLoanIdAndStatusAndDeletedAtIsNull(loanId, ConditionStatus.Outstanding);
    }
}
