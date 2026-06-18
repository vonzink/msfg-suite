package com.msfg.los.identity.repo;

import com.msfg.los.identity.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    // ⚠️ load-by-PK (findById) does NOT honor @TenantId in Hibernate 6 — always scope by org_id
    // so a cross-tenant caller cannot load another org's user row.
    Optional<UserAccount> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<UserAccount> findByOrgIdAndEmail(UUID orgId, String email);
}
