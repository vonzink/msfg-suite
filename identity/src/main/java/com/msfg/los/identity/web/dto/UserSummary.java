package com.msfg.los.identity.web.dto;

import com.msfg.los.identity.domain.UserAccount;

/** A created/administered user, returned to the LO/Admin screen. */
public record UserSummary(String id, String email, String name, String role) {
    public static UserSummary from(UserAccount u) {
        return new UserSummary(u.getId().toString(), u.getEmail(), u.getName(), u.getRole());
    }
}
