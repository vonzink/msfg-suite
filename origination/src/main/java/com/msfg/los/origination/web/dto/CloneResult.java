package com.msfg.los.origination.web.dto;

import java.util.UUID;

/**
 * Result of a "Copy to new" clone (Phase 2 T7): the freshly minted loan's identifiers. Unique simple
 * name (springdoc keys schemas by simple name) so it never collides with another module's DTO.
 */
public record CloneResult(UUID id, String loanNumber) {}
