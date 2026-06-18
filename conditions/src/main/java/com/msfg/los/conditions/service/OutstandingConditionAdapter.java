package com.msfg.los.conditions.service;

import com.msfg.los.conditions.repo.LoanConditionRepository;
import com.msfg.los.loan.service.OutstandingConditionResolver;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Conditions-module adapter for the loan-core {@link OutstandingConditionResolver} port (Phase 2 T4).
 * Lets the loan-core pipeline read outstanding-condition loan-ids WITHOUT loan-core ever importing the
 * conditions repository (ArchUnit module boundary — loan-core sees only the port). The adapter lives in
 * the conditions module, so reaching the module's OWN {@link LoanConditionRepository} is allowed.
 *
 * <p>Deliberately depends on the repository, NOT {@code ConditionService}: {@code ConditionService}
 * depends on {@code LoanService} (cross-module loan access), and {@code LoanService} depends on this
 * adapter — going through the service would close a constructor-injection cycle
 * (loanService → adapter → conditionService → loanService). The grouped count query carries no access
 * decision, so reading it straight from the repo is correct here: the pipeline has already scoped its
 * own results to the caller, and {@code @TenantId} scopes the query to the caller's org.
 */
@Component
public class OutstandingConditionAdapter implements OutstandingConditionResolver {

    private final LoanConditionRepository conditions;

    public OutstandingConditionAdapter(LoanConditionRepository conditions) {
        this.conditions = conditions;
    }

    @Override
    public Set<UUID> loanIdsWithOutstandingOver(int n) {
        return new LinkedHashSet<>(conditions.findLoanIdsWithOutstandingOver(n));
    }
}
