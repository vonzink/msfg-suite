package com.msfg.los.parties.service;

import com.msfg.los.loan.service.BorrowerUserLinker;
import com.msfg.los.parties.repo.BorrowerRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Parties-module adapter implementing the {@code loan-core} port {@link BorrowerUserLinker}.
 *
 * <p>Keeps {@code loan-core}/{@code identity} free of any {@code parties} import (ArchUnit boundary).
 * Repository queries are {@code @TenantId}-filtered, so the link is always scoped to the bound tenant
 * — a matching email in another org is invisible here.
 *
 * <p>The anti-takeover contract (see {@link BorrowerUserLinker#linkByVerifiedEmail}) is enforced in
 * two layers: this method links only when EXACTLY ONE unlinked email match exists, and the
 * underlying UPDATE re-asserts {@code user_id IS NULL} so nothing is ever overwritten (and a race
 * collapses to a no-op).
 */
@Component
public class BorrowerUserLinkAdapter implements BorrowerUserLinker {

    private final BorrowerRepository borrowers;

    public BorrowerUserLinkAdapter(BorrowerRepository borrowers) {
        this.borrowers = borrowers;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public LinkResult linkByVerifiedEmail(String email, UUID userId) {
        if (email == null || email.isBlank() || userId == null) {
            return LinkResult.NO_OP;
        }

        List<UUID> candidates = borrowers.findUnlinkedIdsByEmailIgnoreCase(email);
        // Stamp ONLY on a unique match. Zero or >1 → do nothing (ambiguous → never auto-link).
        if (candidates.size() != 1) {
            return LinkResult.NO_OP;
        }

        int updated = borrowers.linkUserIfUnlinked(candidates.get(0), userId);
        return updated == 1 ? LinkResult.LINKED : LinkResult.NO_OP;
    }
}
