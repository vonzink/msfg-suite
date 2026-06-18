package com.msfg.los.documents.web.dto;

import java.util.UUID;

/** Result of seeding a loan's default folder tree: the root id + display name. */
public record SeedFoldersResponse(
        UUID rootId,
        String rootName) {
}
