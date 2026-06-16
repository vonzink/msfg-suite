package com.msfg.los.documents.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Create a user folder under {@code parentId} (null → the loan's root). */
public record CreateFolderRequest(
        UUID parentId,
        @NotBlank @Size(max = 255) String displayName) {
}
