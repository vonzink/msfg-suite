package com.msfg.los.contacts.web.dto;

import com.msfg.los.contacts.domain.ContactRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateContactRequest(
        @NotNull ContactRole role,
        @NotBlank String name,
        String company,
        String phone,
        String email) {}
