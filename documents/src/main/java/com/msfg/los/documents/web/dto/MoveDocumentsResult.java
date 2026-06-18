package com.msfg.los.documents.web.dto;

import java.util.UUID;

/**
 * Result of {@code POST /move}: how many of the requested ids were actually relocated, and the
 * destination folder (null = unfiled).
 */
public record MoveDocumentsResult(int requested, int moved, UUID toFolderId) {
}
