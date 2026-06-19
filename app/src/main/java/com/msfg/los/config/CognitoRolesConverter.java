package com.msfg.los.config;

import com.msfg.los.platform.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

public class CognitoRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final Logger log = LoggerFactory.getLogger(CognitoRolesConverter.class);

    // The shared Cognito pool (inherited from mortgage-app) emits group strings that do NOT match
    // the suite Role enum names 1:1. This alias map reconciles the pool's actual strings to suite
    // Roles. The dormant "External" pool group has no suite equivalent and is intentionally absent
    // (→ dropped). Matching is case-sensitive / fail-closed: cognito:groups is not a trusted
    // authority source (a misconfigured pre-token-generation Lambda can copy user-editable
    // attributes into it), so unknown strings are dropped, never mapped.
    private static final Map<String, Role> GROUP_ALIASES = Map.of(
            "Admin", Role.ADMIN,
            "Manager", Role.MANAGER,
            "LO", Role.LO,
            "Processor", Role.PROCESSOR,
            "Borrower", Role.BORROWER,
            "RealEstateAgent", Role.REAL_ESTATE_AGENT);

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Collection<GrantedAuthority> auths = new ArrayList<>();
        Object groups = jwt.getClaim("cognito:groups");
        if (groups instanceof Collection<?> g) {
            for (Object group : g) {
                if (group == null) continue;
                Role role = resolve(group.toString());
                if (role != null) {
                    auths.add(new SimpleGrantedAuthority(role.authority()));
                } else {
                    log.debug("Dropped unrecognized cognito:groups entry '{}'", group);
                }
            }
        }
        return auths;
    }

    // Resolve a cognito:groups string to a suite Role: alias map first, then exact Role enum name
    // (keeps already-aligned/future groups like UNDERWRITER/CLOSER/PLATFORM_ADMIN working).
    // Case-sensitive; returns null for anything unrecognized.
    private static Role resolve(String group) {
        Role alias = GROUP_ALIASES.get(group);
        if (alias != null) return alias;
        try {
            return Role.valueOf(group);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
