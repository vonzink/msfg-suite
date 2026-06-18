package com.msfg.los.loan.service;

import org.springframework.data.domain.Sort;

import java.util.Locale;
import java.util.Map;

/**
 * Injection-safe sort resolution for the pipeline (Phase 2 T4). Only whitelisted field tokens map to
 * a known entity property; anything else (unknown field, garbage, SQL-injection attempt) falls back
 * to the default newest-first ordering. Direction defaults to {@code desc} unless {@code asc} is
 * explicitly given.
 *
 * <p>Default ordering is {@code createdAt DESC, id DESC} — the id tiebreaker gives a stable total
 * order so paging is deterministic when createdAt collides.
 */
public final class PipelineSort {

    private PipelineSort() {
    }

    /** {@code sort} token → entity property name. Closed set; nothing else is sortable. */
    private static final Map<String, String> WHITELIST = Map.of(
            "createdat", "createdAt",
            "statuschangedat", "statusChangedAt",
            "amount", "baseLoanAmount");

    /** Stable default: newest first, with an id tiebreaker for deterministic paging. */
    public static final Sort DEFAULT =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /**
     * Parse a {@code sort} param of the form {@code field,dir} (dir optional). Returns the whitelisted
     * ordering plus an id tiebreaker, or {@link #DEFAULT} when {@code raw} is null/blank/unrecognized.
     */
    public static Sort parse(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        String[] parts = raw.split(",", 2);
        String field = parts[0].trim().toLowerCase(Locale.ROOT);
        String property = WHITELIST.get(field);
        if (property == null) return DEFAULT;   // unknown/garbage field → safe fallback
        boolean asc = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim());
        Sort.Order primary = asc ? Sort.Order.asc(property) : Sort.Order.desc(property);
        // id tiebreaker keeps paging deterministic; follow the primary direction.
        Sort.Order tiebreaker = asc ? Sort.Order.asc("id") : Sort.Order.desc("id");
        return Sort.by(primary, tiebreaker);
    }
}
