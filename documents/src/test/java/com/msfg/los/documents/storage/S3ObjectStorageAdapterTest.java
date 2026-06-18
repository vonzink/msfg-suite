package com.msfg.los.documents.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3ObjectStorageAdapterTest {

    private S3Client s3;
    private S3Presigner presigner;
    private S3ObjectStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        presigner = mock(S3Presigner.class);
        adapter = new S3ObjectStorageAdapter(s3, presigner, "my-bucket");
    }

    @Test
    void presignUploadBuildsPutRequestWithTtlAndContentType() throws Exception {
        var presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("https://my-bucket.s3.amazonaws.com/applications/x?sig=put"));
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);

        String url = adapter.presignUpload("applications/x", "application/pdf", Duration.ofMinutes(15));

        assertThat(url).contains("sig=put");
        var captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(presigner).presignPutObject(captor.capture());
        PutObjectPresignRequest req = captor.getValue();
        assertThat(req.signatureDuration()).isEqualTo(Duration.ofMinutes(15));
        PutObjectRequest put = req.putObjectRequest();
        assertThat(put.bucket()).isEqualTo("my-bucket");
        assertThat(put.key()).isEqualTo("applications/x");
        assertThat(put.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void presignDownloadBuildsGetRequestWithAttachmentDispositionAndTtl() throws Exception {
        var presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("https://my-bucket.s3.amazonaws.com/applications/x?sig=get"));
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        String url = adapter.presignDownload("applications/x", "My Report.pdf", Duration.ofMinutes(10));

        assertThat(url).contains("sig=get");
        var captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(captor.capture());
        GetObjectPresignRequest req = captor.getValue();
        assertThat(req.signatureDuration()).isEqualTo(Duration.ofMinutes(10));
        GetObjectRequest get = req.getObjectRequest();
        assertThat(get.bucket()).isEqualTo("my-bucket");
        assertThat(get.key()).isEqualTo("applications/x");
        assertThat(get.responseContentDisposition()).isEqualTo("attachment; filename=\"My Report.pdf\"");
    }

    @Test
    void headSizeReturnsContentLength() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(4242L).build());

        assertThat(adapter.headSize("applications/x")).isEqualTo(4242L);

        var captor = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3).headObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("my-bucket");
        assertThat(captor.getValue().key()).isEqualTo("applications/x");
    }

    @Test
    void headSizeReturnsMinusOneWhenAbsent() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("nope").build());

        assertThat(adapter.headSize("missing")).isEqualTo(-1L);
    }

    @Test
    void sha256HashesStoredBytes() throws Exception {
        byte[] payload = "hello-pdf".getBytes(StandardCharsets.UTF_8);
        var ris = new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(payload)));
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(ris);

        String hex = adapter.sha256("applications/x");

        byte[] expected = MessageDigest.getInstance("SHA-256").digest(payload);
        StringBuilder sb = new StringBuilder();
        for (byte b : expected) sb.append(String.format("%02x", b));
        assertThat(hex).isEqualTo(sb.toString());
    }

    @Test
    void sha256ReturnsNullOnFailure() {
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenThrow(AwsServiceException.builder().message("boom").build());

        assertThat(adapter.sha256("applications/x")).isNull();
    }

    @Test
    void tagCallsPutObjectTagging() {
        adapter.tag("applications/x", Map.of("sensitivity", "confidential", "loan_id", "L1"));

        var captor = ArgumentCaptor.forClass(PutObjectTaggingRequest.class);
        verify(s3).putObjectTagging(captor.capture());
        PutObjectTaggingRequest req = captor.getValue();
        assertThat(req.bucket()).isEqualTo("my-bucket");
        assertThat(req.key()).isEqualTo("applications/x");
        assertThat(req.tagging().tagSet()).extracting("key")
                .containsExactlyInAnyOrder("sensitivity", "loan_id");
    }

    @Test
    void deleteCallsDeleteObject() {
        adapter.delete("applications/x");
        verify(s3).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }

    @Test
    void storeAndLoadUsePutAndGet() throws Exception {
        adapter.store("applications/x", "bytes".getBytes(StandardCharsets.UTF_8), "application/pdf");
        verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        byte[] payload = "loaded".getBytes(StandardCharsets.UTF_8);
        var ris = new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(payload)));
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(ris);

        assertThat(adapter.load("applications/x")).isEqualTo(payload);
    }
}
