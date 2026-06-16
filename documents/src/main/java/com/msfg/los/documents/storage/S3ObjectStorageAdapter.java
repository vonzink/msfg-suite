package com.msfg.los.documents.storage;

import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.storage.BlobStoragePort;
import com.msfg.los.platform.storage.ObjectStoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Production object-storage adapter backed by AWS S3 (SDK v2). Implements both
 * {@link ObjectStoragePort} (presigned upload/download + head/sha256/tag) and
 * {@link BlobStoragePort} (store/load/delete for generated artifacts) so a single bean serves
 * every storage seam when {@code los.storage.driver=s3}.
 *
 * <p>Bucket/region come from {@code los.storage.s3.*}; the {@link S3Client}/{@link S3Presigner}
 * beans (default credential chain) are wired in {@link S3StorageConfig}. WORM/Object-Lock/lifecycle
 * is bucket/CDK policy (Phase 6) — this adapter only emits tags.
 */
@Component
@ConditionalOnProperty(name = "los.storage.driver", havingValue = "s3")
public class S3ObjectStorageAdapter implements ObjectStoragePort, BlobStoragePort {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorageAdapter.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ObjectStorageAdapter(S3Client s3,
                                  S3Presigner presigner,
                                  @Value("${los.storage.s3.bucket}") String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public String presignUpload(String key, String contentType, Duration ttl) {
        PutObjectRequest.Builder put = PutObjectRequest.builder().bucket(bucket).key(key);
        if (contentType != null) {
            put.contentType(contentType);
        }
        PutObjectPresignRequest req = PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(put.build())
                .build();
        return presigner.presignPutObject(req).url().toString();
    }

    @Override
    public String presignDownload(String key, String filename, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .responseContentDisposition("attachment; filename=\"" + filename + "\"")
                .build();
        GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(get)
                .build();
        return presigner.presignGetObject(req).url().toString();
    }

    @Override
    public long headSize(String key) {
        try {
            return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
                    .contentLength();
        } catch (NoSuchKeyException e) {
            return -1L;
        }
    }

    @Override
    public String sha256(String key) {
        try (ResponseInputStream<GetObjectResponse> in =
                     s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("sha256 best-effort failed for key {}: {}", key, e.toString());
            return null;
        }
    }

    @Override
    public void tag(String key, Map<String, String> tags) {
        List<Tag> tagSet = tags.entrySet().stream()
                .map(e -> Tag.builder().key(e.getKey()).value(e.getValue()).build())
                .toList();
        s3.putObjectTagging(PutObjectTaggingRequest.builder()
                .bucket(bucket)
                .key(key)
                .tagging(Tagging.builder().tagSet(tagSet).build())
                .build());
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    // --- BlobStoragePort (generated artifacts: pre-approval letters, lock confirmations, …) ---

    @Override
    public void store(String storageKey, byte[] bytes, String contentType) {
        PutObjectRequest.Builder put = PutObjectRequest.builder().bucket(bucket).key(storageKey);
        if (contentType != null) {
            put.contentType(contentType);
        }
        s3.putObject(put.build(), RequestBody.fromBytes(bytes));
    }

    @Override
    public byte[] load(String storageKey) {
        try (ResponseInputStream<GetObjectResponse> in =
                     s3.getObject(GetObjectRequest.builder().bucket(bucket).key(storageKey).build())) {
            return in.readAllBytes();
        } catch (NoSuchKeyException e) {
            throw new NotFoundException("Document content", storageKey);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read S3 object " + storageKey, e);
        }
    }

    // delete(String) above satisfies BlobStoragePort.delete too (same signature, idempotent).
}
