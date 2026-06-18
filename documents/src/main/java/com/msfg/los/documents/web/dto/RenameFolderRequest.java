package com.msfg.los.documents.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Rename a folder (system folders are renamable). */
public record RenameFolderRequest(
        @NotBlank @Size(max = 255) String displayName) {
}
