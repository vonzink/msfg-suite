package com.msfg.los.config;

import com.msfg.los.platform.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

public class CognitoRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final Logger log = LoggerFactory.getLogger(CognitoRolesConverter.class);

    // Authorities are minted ONLY for exact Role enum names (case-sensitive): cognito:groups
    // is not a trusted authority source — a misconfigured pre-token-generation Lambda can copy
    // user-editable attributes into it. Unknown strings are dropped, never mapped.
    private static final Set<String> KNOWN_ROLES = Arrays.stream(Role.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Collection<GrantedAuthority> auths = new ArrayList<>();
        Object groups = jwt.getClaim("cognito:groups");
        if (groups instanceof Collection<?> g) {
            for (Object role : g) {
                if (role == null) continue;
                if (KNOWN_ROLES.contains(role.toString())) {
                    auths.add(new SimpleGrantedAuthority("ROLE_" + role));
                } else {
                    log.debug("Dropped unrecognized cognito:groups entry '{}'", role);
                }
            }
        }
        return auths;
    }
}
