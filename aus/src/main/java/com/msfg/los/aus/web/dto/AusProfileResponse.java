package com.msfg.los.aus.web.dto;

/** Per-loan AUS profile: DU + LPA submission settings, always both present (empty defaults). */
public record AusProfileResponse(AusVendorSettingsView du, AusVendorSettingsView lpa) {}
