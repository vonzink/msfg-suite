package com.msfg.los.identity.service;

import com.msfg.los.identity.domain.UserAccount;
import com.msfg.los.identity.repo.UserAccountRepository;
import com.msfg.los.platform.error.ForbiddenException;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.security.Role;
import com.msfg.los.platform.security.UserAdminPort;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * LO/Admin user administration: create a user in the IdP (via {@link UserAdminPort}) and
 * materialize a tenant-scoped {@code user_account} row; trigger password resets. The new user's org
 * is always the acting admin's org ({@link TenantContext#requireOrgId()}) — no cross-tenant create.
 */
@Service
public class UserAdminService {

    /** Roles a non-ADMIN staffer (i.e. an LO) may assign. Staff/admin roles require ADMIN. */
    private static final Set<String> LO_ASSIGNABLE_ROLES =
            Set.of(Role.BORROWER.name(), Role.REAL_ESTATE_AGENT.name());

    private final UserAdminPort userAdmin;
    private final UserAccountRepository users;
    private final TenantContext tenantContext;
    private final CurrentUser currentUser;

    public UserAdminService(UserAdminPort userAdmin, UserAccountRepository users,
                            TenantContext tenantContext, CurrentUser currentUser) {
        this.userAdmin = userAdmin;
        this.users = users;
        this.tenantContext = tenantContext;
        this.currentUser = currentUser;
    }

    @Transactional
    public UserAccount createUser(String email, String name, String role) {
        UUID org = tenantContext.requireOrgId();
        assertMayAssignRole(role);
        String sub = userAdmin.createUser(new UserAdminPort.NewUser(email, name, role));
        UserAccount u = new UserAccount();
        u.setId(UUID.fromString(sub));
        u.setOrgId(org);
        u.setEmail(email);
        u.setName(name);
        u.setInitials(UserAccountService.initials(name));
        u.setRole(role);
        return users.save(u);
    }

    /**
     * Trigger an IdP password reset for an in-org user. 404 if the user is not in the caller's org;
     * an LO may reset only borrower/agent users (same escalation guard as create — keyed on the
     * TARGET user's role), so an LO cannot reset a staff/admin password.
     */
    @Transactional
    public void resetPassword(UUID userId) {
        UUID org = tenantContext.requireOrgId();
        UserAccount target = users.findByIdAndOrgId(userId, org)
                .orElseThrow(() -> new NotFoundException("User", userId.toString()));
        assertMayAssignRole(target.getRole());
        userAdmin.resetPassword(target.getId().toString());
    }

    /**
     * Privilege-escalation guard: ADMIN may assign any role; an LO may create ONLY external parties
     * (borrower/agent) — never a staff or admin user. Both roles pass the URL filter, so this is the
     * authority that stops an LO from minting itself a peer or an admin.
     */
    private void assertMayAssignRole(String role) {
        if (currentUser.roles().contains(Role.ADMIN.authority())) {
            return;
        }
        if (role == null || !LO_ASSIGNABLE_ROLES.contains(role)) {
            throw new ForbiddenException("A loan officer may only create borrower or agent users");
        }
    }
}
