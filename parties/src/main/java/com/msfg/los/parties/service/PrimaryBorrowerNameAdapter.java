package com.msfg.los.parties.service;

import com.msfg.los.loan.service.PrimaryBorrowerNameResolver;
import com.msfg.los.parties.domain.BorrowerParty;
import com.msfg.los.parties.repo.BorrowerRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class PrimaryBorrowerNameAdapter implements PrimaryBorrowerNameResolver {

    private final BorrowerRepository borrowers;

    public PrimaryBorrowerNameAdapter(BorrowerRepository borrowers) {
        this.borrowers = borrowers;
    }

    @Override
    public Map<UUID, String> primaryBorrowerNamesByLoanIds(Collection<UUID> loanIds) {
        Map<UUID, String> out = new HashMap<>();
        if (loanIds == null || loanIds.isEmpty()) return out;
        for (BorrowerParty b : borrowers.findByLoanIdInAndPrimaryTrue(loanIds)) {
            out.putIfAbsent(b.getLoanId(), name(b));   // first primary per loan wins
        }
        return out;
    }

    private static String name(BorrowerParty b) {
        String f = b.getFirstName() == null ? "" : b.getFirstName();
        String l = b.getLastName() == null ? "" : b.getLastName();
        String full = (f + " " + l).trim();
        return full.isEmpty() ? null : full;
    }
}
