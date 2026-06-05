package com.msfg.los.support;

import com.msfg.los.platform.tenancy.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(AbstractIntegrationTest.TestBeans.class)
public abstract class AbstractIntegrationTest {

    public static final String DEFAULT_ORG = "00000000-0000-0000-0000-0000000000aa";

    // Singleton container — started once per JVM, never stopped by the JUnit5 extension.
    // @Testcontainers + @Container on a superclass stops the container after each subclass
    // (even static containers), which kills the pool for the next class in the same JVM run.
    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        reg.add("spring.datasource.username", POSTGRES::getUsername);
        reg.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> { throw new UnsupportedOperationException("JwtDecoder not used in tests"); };
        }
    }
}
