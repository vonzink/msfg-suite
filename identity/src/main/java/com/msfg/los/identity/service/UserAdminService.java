package com.msfg.los.identity.service;

import com.msfg.los.identity.domain.UserAccount;
import com.msfg.los.identity.repo.UserAccountRepository;
import com.msfg.los.platform.error.ConflictException;
import com.msfg.los.platform.error.ForbiddenException;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.security.Role;
import com.msfg.los.platform.security.UserAdminPort;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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

    /** Pragmatic email shape check (one @, a dot in the domain, no whitespace). */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

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
        String normalizedEmail = normalizeEmail(email);   // 400 on blank/malformed
        assertValidRole(role);                            // 400 on blank/unknown role
        assertMayAssignRole(role);                        // 403 if caller may not assign this role
        // Pre-check before touching the IdP so a duplicate never orphans a Cognito user (the DB
        // unique key on (org, email) is the backstop).
        if (users.findByOrgIdAndEmail(org, normalizedEmail).isPresent()) {
            throw new ConflictException("A user with this email already exists in this organization");
        }
        String sub = userAdmin.createUser(new UserAdminPort.NewUser(normalizedEmail, name, role));
        UserAccount u = new UserAccount();
        u.setId(UUID.fromString(sub));
        u.setOrgId(org);
        u.setEmail(normalizedEmail);
        u.setName(name);
        u.setInitials(UserAccountService.initials(name));
        u.setRole(role);
        return users.save(u);
    }

    private static String normalizeEmail(String email) {
        String e = email == null ? "" : email.trim();
        if (e.isEmpty() || !EMAIL.matcher(e).matches()) {
            throw new ValidationException("A valid email is required");
        }
        return e;
    }

    private static void assertValidRole(String role) {
        if (role == null || role.isBlank()) {
            throw new ValidationException("A role is required");
        }
        boolean known = Arrays.stream(Role.values()).anyMatch(r -> r.name().equals(role));
        if (!known) {
            throw new ValidationException("Unknown role: " + role);
        }
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
