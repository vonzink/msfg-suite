package com.msfg.los.declarations.domain;

import jakarta.persistence.AttributeConverter;
import java.util.*;
import java.util.stream.Collectors;

public abstract class EnumSetConverter<E extends Enum<E>> implements AttributeConverter<Set<E>, String> {
    private final Class<E> type;
    protected EnumSetConverter(Class<E> type) { this.type = type; }

    @Override
    public String convertToDatabaseColumn(Set<E> attr) {
        return (attr == null || attr.isEmpty()) ? null
            : attr.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    @Override
    public Set<E> convertToEntityAttribute(String db) {
        Set<E> out = new LinkedHashSet<>();
        if (db == null || db.isBlank()) return out;
        for (String s : db.split(",")) { String t = s.trim(); if (!t.isEmpty()) out.add(Enum.valueOf(type, t)); }
        return out;
    }
}
