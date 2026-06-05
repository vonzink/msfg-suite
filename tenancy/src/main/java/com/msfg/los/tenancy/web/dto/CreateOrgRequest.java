package com.msfg.los.tenancy.web.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
public record CreateOrgRequest(@NotBlank String name,
    @NotBlank @Pattern(regexp = "^[a-z0-9-]{2,100}$") String slug) {}
