package com.msfg.los.identity.web.dto;

import com.msfg.los.identity.domain.UserAccount;

import java.util.List;
import java.util.UUID;

/** The authenticated caller's identity: persisted profile + live JWT authorities. */
public record MeResponse(
        UUID id,
        String email,
        String name,
        String initials,
        String role,
        UUID orgId,
        List<String> roles) {

    public static MeResponse from(UserAccount u, List<String> roles) {
        return new MeResponse(
                u.getId(),
                u.getEmail(),
                u.getName(),
                u.getInitials(),
                u.getRole(),
                u.getOrgId(),
                roles);
    }
}
