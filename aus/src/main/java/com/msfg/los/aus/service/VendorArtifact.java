package com.msfg.los.aus.service;

/** A vendor-returned document (findings HTML/XML, feedback certificate, credit report...). */
public record VendorArtifact(String name, String contentType, byte[] bytes) {}
