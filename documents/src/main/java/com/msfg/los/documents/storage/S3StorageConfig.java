package com.msfg.los.documents.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Wires the AWS S3 client + presigner from {@code los.storage.s3.region} using the default
 * credential provider chain (env / profile / container role / IMDS). Only active when
 * {@code los.storage.driver=s3}; in the default {@code db} mode no AWS beans are created and the
 * SDK never touches the network at startup.
 */
@Configuration
@ConditionalOnProperty(name = "los.storage.driver", havingValue = "s3")
public class S3StorageConfig {

    @Bean
    S3Client s3Client(@Value("${los.storage.s3.region}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build();
    }

    @Bean
    S3Presigner s3Presigner(@Value("${los.storage.s3.region}") String region) {
        return S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build();
    }
}
