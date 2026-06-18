package com.msfg.los.documents.web.dto;

import java.util.UUID;

/**
 * Step-1 response of the 3-step presigned upload flow.
 *
 * @param documentId       id of the created {@code PENDING_UPLOAD} document (public, non-enumerable handle)
 * @param s3Key            server-minted storage key the bytes will live under
 * @param uploadUrl        presigned URL to PUT the raw bytes to (local adapter → an internal,
 *                         authenticated receive endpoint; S3 adapter → a real presigned PUT URL)
 * @param contentType      MIME type the upload URL expects
 * @param expiresInSeconds TTL of the presigned URL in seconds
 */
public record UploadUrlResponse(
        UUID documentId,
        String s3Key,
        String uploadUrl,
        String contentType,
        long expiresInSeconds) {
}
