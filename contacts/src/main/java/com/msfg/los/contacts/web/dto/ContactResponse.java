package com.msfg.los.contacts.web.dto;

import com.msfg.los.contacts.domain.Contact;
import com.msfg.los.contacts.domain.ContactRole;

import java.util.UUID;

public record ContactResponse(
        UUID id,
        ContactRole role,
        String name,
        String company,
        String phone,
        String email,
        int ordinal) {

    public static ContactResponse from(Contact c) {
        return new ContactResponse(
                c.getId(),
                c.getRole(),
                c.getName(),
                c.getCompany(),
                c.getPhone(),
                c.getEmail(),
                c.getOrdinal());
    }
}
