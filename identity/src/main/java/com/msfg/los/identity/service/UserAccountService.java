package com.msfg.los.identity.service;

import com.msfg.los.identity.domain.UserAccount;
import com.msfg.los.identity.repo.UserAccountRepository;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.security.Role;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Materialize-on-first-call resolver for the tenant-scoped {@code user_account} row.
 *
 * <p>The first authenticated {@code /me} for a (sub, org) inserts the row; subsequent calls return
 * it, refreshing name/role only when the JWT now disagrees with what's persisted. Lookup is always
 * tenant-scoped ({@code findByIdAndOrgId} — load-by-PK does not honor {@code @TenantId}).
 */
@Service
public class UserAccountService {

    /** Highest-priority staff group wins as the persisted primary role. */
    private static final List<Role> ROLE_PRIORITY = List.of(
            Role.ADMIN, Role.MANAGER, Role.UNDERWRITER, Role.CLOSER, Role.PROCESSOR, Role.LO);

    private static final Pattern WORD_SPLIT = Pattern.compile("[\\s.,_-]+");

    private final UserAccountRepository users;
    private final CurrentUser currentUser;
    private final TenantContext tenantContext;

    public UserAccountService(UserAccountRepository users, CurrentUser currentUser,
                              TenantContext tenantContext) {
        this.users = users;
        this.currentUser = currentUser;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public UserAccount resolveOrCreate() {
        UUID org = tenantContext.requireOrgId();
        UUID sub = UUID.fromString(currentUser.id().orElseThrow(
                () -> new IllegalStateException("authenticated principal has no subject")));

        String email = currentUser.email()
                .filter(e -> !e.isBlank())
                .orElse(sub + "@unknown.local");
        String name = currentUser.name()
                .filter(n -> !n.isBlank())
                .orElse(email);
        String role = primaryRole(currentUser.roles());

        // Prefer the PK match; fall back to the (org, email) unique key for a pre-existing row
        // whose id differs (e.g. email re-pointed to a new sub).
        UserAccount existing = users.findByIdAndOrgId(sub, org)
                .or(() -> users.findByOrgIdAndEmail(org, email))
                .orElse(null);

        if (existing != null) {
            boolean dirty = false;
            if (name != null && !name.equals(existing.getName())) {
                existing.setName(name);
                existing.setInitials(initials(name));
                dirty = true;
            }
            if (role != null && !role.equals(existing.getRole())) {
                existing.setRole(role);
                dirty = true;
            }
            return dirty ? users.save(existing) : existing;
        }

        UserAccount u = new UserAccount();
        u.setId(sub);
        u.setOrgId(org);
        u.setEmail(email);
        u.setName(name);
        u.setInitials(initials(name));
        u.setRole(role);
        return users.save(u);
    }

    /** First-letter-of-each-word, up to 3, uppercase. */
    static String initials(String name) {
        if (name == null || name.isBlank()) return null;
        StringBuilder sb = new StringBuilder();
        for (String part : WORD_SPLIT.split(name.trim())) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (sb.length() == 3) break;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** The highest-priority {@link Role} present in the caller's authorities, as its enum name. */
    static String primaryRole(Set<String> authorities) {
        if (authorities == null || authorities.isEmpty()) return null;
        for (Role r : ROLE_PRIORITY) {
            if (authorities.contains(r.authority())) return r.name();
        }
        return null;
    }
}
