package com.msfg.los.coc.domain;

public record StructureChange(
        String field,
        String label,
        String currentValue,
        String requestedValue
) {}
