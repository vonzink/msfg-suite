package com.msfg.los.documents.web.dto;

/**
 * Response of {@code GET /{docId}/download-url}: a presigned URL to GET the document bytes as an
 * attachment named after the stored {@code fileName}, plus its TTL.
 *
 * @param downloadUrl      presigned URL to GET the bytes (local adapter → an internal,
 *                         authenticated serve endpoint; S3 adapter → a real presigned GET URL)
 * @param expiresInSeconds TTL of the presigned URL in seconds
 */
public record DownloadUrlResponse(String downloadUrl, long expiresInSeconds) {
}
