package com.msfg.los.disclosures.service;

public record UcdExportResult(String vendorReference, String mismoVersion, byte[] ucdXml) {}
