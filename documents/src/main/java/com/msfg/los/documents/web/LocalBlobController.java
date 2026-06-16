package com.msfg.los.documents.web;

import com.msfg.los.documents.storage.LocalObjectStorageAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal, AUTHENTICATED receive/serve endpoints that back {@link LocalObjectStorageAdapter}'s
 * presigned URLs in the default {@code db} storage mode (no real object store locally). The PUT
 * stores raw bytes under the (base64url-token-encoded) storage key; the GET streams them back —
 * letting ITs run the real upload-url → PUT → confirm round-trip with no S3.
 *
 * <p>Gated to {@code los.storage.driver=db}: when driver=s3 this controller is NOT registered
 * (clients PUT directly to S3 presigned URLs instead). Lives under {@code /api/**}, so the
 * existing security chain requires authentication — this is NOT an unauthenticated route.
 */
@RestController
@RequestMapping("/api/_local-blob/{token}")
@ConditionalOnProperty(name = "los.storage.driver", havingValue = "db", matchIfMissing = true)
public class LocalBlobController {

    private final LocalObjectStorageAdapter storage;

    public LocalBlobController(LocalObjectStorageAdapter storage) {
        this.storage = storage;
    }

    @PutMapping
    public ResponseEntity<Void> receive(
            @PathVariable String token,
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
        String key = LocalObjectStorageAdapter.decodeToken(token);
        storage.store(key, body == null ? new byte[0] : body, contentType);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ResponseEntity<byte[]> serve(@PathVariable String token) {
        String key = LocalObjectStorageAdapter.decodeToken(token);
        byte[] bytes = storage.load(key); // NotFoundException → 404 if absent
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }
}
