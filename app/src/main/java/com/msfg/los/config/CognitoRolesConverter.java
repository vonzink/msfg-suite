package com.msfg.los.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;

public class CognitoRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Collection<GrantedAuthority> auths = new ArrayList<>();
        Object groups = jwt.getClaim("cognito:groups");
        if (groups instanceof Collection<?> g) {
            for (Object role : g) auths.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return auths;
    }
}
