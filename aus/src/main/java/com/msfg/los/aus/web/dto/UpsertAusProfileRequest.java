package com.msfg.los.aus.web.dto;

import com.msfg.los.aus.domain.AusVendorSettings;

/** PUT-upsert body. A null vendor block means "leave that vendor's settings unchanged". */
public record UpsertAusProfileRequest(AusVendorSettings du, AusVendorSettings lpa) {}
