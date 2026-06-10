package com.msfg.los.loan.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface PrimaryBorrowerNameResolver {
    Map<UUID, String> primaryBorrowerNamesByLoanIds(Collection<UUID> loanIds);
}
