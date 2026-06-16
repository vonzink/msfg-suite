package com.msfg.los.documents.web.dto;

import java.util.List;
import java.util.UUID;

/** A loan's folder tree: the root id, the live folder count, and the ordered nodes. */
public record FolderTreeResponse(
        UUID rootId,
        int count,
        List<FolderResponse> folders) {
}
