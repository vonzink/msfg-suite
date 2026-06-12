package com.msfg.los.contacts.web.dto;

import com.msfg.los.contacts.domain.ContactRole;

/** All fields optional — provided-field (PATCH) semantics; null = leave unchanged. */
public record UpdateContactRequest(
        ContactRole role,
        String name,
        String company,
        String phone,
        String email) {}
