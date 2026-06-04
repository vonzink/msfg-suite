# LOS Spec 1 — Foundation + Core Loan Spine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a Java 21 / Spring Boot 3 / Postgres modular-monolith backend with a createable, lifecycle-managed, access-controlled **Loan** that lists in a pipeline — the spine every later subsystem hangs off.

**Architecture:** Gradle multi-module modular monolith (`platform`, `app`, `loan-core`, `parties`). Pragmatic layered domain (JPA entities + service layer + a focused domain layer for the loan lifecycle). REST + OpenAPI. AWS Cognito JWT auth via Spring Security resource server with group→role RBAC. MISMO/ULAD-aligned enum/field naming. NPI encryption util established now. Flyway migrations. Testcontainers + MockMvc tests, TDD throughout.

**Tech Stack:** Java 21, Spring Boot 3.3.x, Gradle (Kotlin DSL), Postgres 16, Flyway, Spring Security (oauth2-resource-server), springdoc-openapi, Lombok, JUnit 5, Testcontainers, spring-security-test.

**Repo root:** `/Users/zacharyzink/MSFG/msfg-suite` · **Base package:** `com.msfg.los`

> **Execution note:** At Task 1, invoke `anthropic-skills:java-skills` to confirm scaffold conventions (package layout, security config, AWS wiring) before locking them — adjust file layout below if the standard differs. Use `anthropic-skills:mortgage-skill` when in doubt on ULAD field/enum semantics.

---

## File Structure

```
msfg-suite/
  settings.gradle.kts              include platform, app, loan-core, parties
  build.gradle.kts                 root: plugins (no-op apply false), repositories via subprojects
  gradle/libs.versions.toml        version catalog (single source of versions)
  gradlew · gradle/wrapper/        Gradle wrapper (8.10+)
  docker-compose.yml               local Postgres 16
  .gitignore · README.md
  docs/specs/2026-06-03-los-spec1-foundation-loan-spine.md   (copy of the approved spec)

  platform/  (depends on: spring web, data-jpa, validation; NO app deps)
    build.gradle.kts
    src/main/java/com/msfg/los/platform/
      domain/BaseEntity.java                 UUID id + @Version + equals/hashCode by id
      domain/AuditableEntity.java            extends BaseEntity; created/updated by/at
      web/ApiResponse.java                   success envelope (record)
      web/ApiError.java                      error envelope (record)
      web/GlobalExceptionHandler.java        @RestControllerAdvice → ApiError + status
      web/PagedResponse.java                 pagination envelope (record)
      error/DomainException.java             RuntimeException + HttpStatus + code
      error/NotFoundException.java           404
      error/ConflictException.java           409
      error/ForbiddenException.java          403
      security/Role.java                     enum LO, PROCESSOR, UNDERWRITER, CLOSER, ADMIN
      security/CurrentUser.java              reads JWT principal (id, roles) from SecurityContext
      id/LoanNumberGenerator.java            interface + sequence-backed impl
      crypto/NpiCipher.java                  AES-256-GCM encrypt/decrypt (random IV)
      crypto/EncryptedStringConverter.java   JPA AttributeConverter (autoApply=false)
    src/test/java/com/msfg/los/platform/
      crypto/NpiCipherTest.java
      id/LoanNumberGeneratorTest.java
      web/GlobalExceptionHandlerTest.java

  app/  (depends on: platform, loan-core, parties; all starters)
    build.gradle.kts
    src/main/java/com/msfg/los/
      LosApplication.java
      config/JpaAuditingConfig.java          AuditorAware<String> ← CurrentUser
      config/SecurityConfig.java             resource server, jwt→roles, route rules
      config/OpenApiConfig.java              bearer scheme
    src/main/resources/
      application.yml                        base (jpa, flyway, springdoc, actuator)
      application-local.yml                  local datasource + permissive issuer for dev
      application-dev.yml · application-prod.yml   (issuer-uri placeholders)
      db/migration/V1__loan_core.sql
      db/migration/V2__parties.sql
    src/test/java/com/msfg/los/
      support/AbstractIntegrationTest.java   Testcontainers Postgres (@ServiceConnection)
      LosApplicationTests.java               context loads
      config/SecurityConfigTest.java         401/403/200 via spring-security-test jwt()
    Dockerfile

  loan-core/  (depends on: platform)
    build.gradle.kts
    src/main/java/com/msfg/los/loan/
      domain/LoanPurposeType.java MortgageType.java LienPriorityType.java AmortizationType.java
      domain/LoanStatus.java                 enum (lifecycle states)
      domain/Loan.java                       aggregate root entity
      domain/SubjectProperty.java            @Embeddable
      domain/LoanStatusHistory.java          entity
      domain/LoanLifecycle.java              transition guard (legal moves + role gating)
      repo/LoanRepository.java
      repo/LoanStatusHistoryRepository.java
      service/LoanService.java
      service/LoanAccessGuard.java           loan-scoped authz (ADMIN all; else owner/assignee)
      web/LoanController.java
      web/dto/CreateLoanRequest.java UpdateLoanRequest.java TransitionRequest.java
      web/dto/LoanSummaryResponse.java LoanListItemResponse.java
    src/test/java/com/msfg/los/loan/
      domain/LoanLifecycleTest.java          (unit)
      service/LoanServiceIT.java             (Testcontainers)
      web/LoanControllerIT.java              (MockMvc + jwt())

  parties/  (depends on: platform, loan-core)
    build.gradle.kts
    src/main/java/com/msfg/los/parties/
      domain/BorrowerParty.java
      repo/BorrowerRepository.java
      service/BorrowerService.java
      web/BorrowerController.java
      web/dto/AddBorrowerRequest.java UpdateBorrowerRequest.java BorrowerResponse.java
    src/test/java/com/msfg/los/parties/
      service/BorrowerServiceIT.java
      web/BorrowerControllerIT.java
```

**Module deps:** `loan-core → platform`; `parties → platform, loan-core`; `app → platform, loan-core, parties`. Flyway migrations live centrally in `app` (one DB).

---

## Task 0: Repo + Gradle multi-module scaffold

**Files:** Create `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `.gitignore`, `docker-compose.yml`, `README.md`, Gradle wrapper, empty `build.gradle.kts` per module.

- [ ] **Step 1: `git init` and create `.gitignore`**

```bash
cd /Users/zacharyzink/MSFG/msfg-suite && git init
```

`.gitignore`:
```
.gradle/
build/
*.class
.idea/
*.iml
.DS_Store
/data/         # local postgres volume
.env
```

- [ ] **Step 2: Version catalog** — `gradle/libs.versions.toml`

```toml
[versions]
springBoot = "3.3.5"
springDepMgmt = "1.1.6"
springdoc = "2.6.0"
testcontainers = "1.20.3"

[libraries]
springdoc-openapi = { module = "org.springdoc:springdoc-openapi-starter-webmvc-ui", version.ref = "springdoc" }
testcontainers-bom = { module = "org.testcontainers:testcontainers-bom", version.ref = "testcontainers" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "springBoot" }
spring-dep-mgmt = { id = "io.spring.dependency-management", version.ref = "springDepMgmt" }
```

- [ ] **Step 3: `settings.gradle.kts`**

```kotlin
rootProject.name = "msfg-los"
include("platform", "app", "loan-core", "parties")
```

- [ ] **Step 4: Root `build.gradle.kts`** — shared Java/test config for all subprojects

```kotlin
plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dep.mgmt) apply false
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
    repositories { mavenCentral() }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5")
            mavenBom("org.testcontainers:testcontainers-bom:1.20.3")
        }
    }

    dependencies {
        "compileOnly"("org.projectlombok:lombok")
        "annotationProcessor"("org.projectlombok:lombok")
        "testCompileOnly"("org.projectlombok:lombok")
        "testAnnotationProcessor"("org.projectlombok:lombok")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    }

    tasks.withType<Test> { useJUnitPlatform() }
}
```

- [ ] **Step 5: Per-module `build.gradle.kts` (libraries, not bootJar)**

`platform/build.gradle.kts`:
```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
```

`loan-core/build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":platform"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
```

`parties/build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":platform"))
    implementation(project(":loan-core"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
```

`app/build.gradle.kts` (the only `bootJar`):
```kotlin
plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(project(":platform"))
    implementation(project(":loan-core"))
    implementation(project(":parties"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(libs.springdoc.openapi)
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}
```

- [ ] **Step 6: `docker-compose.yml`** (local Postgres)

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: msfg_los
      POSTGRES_USER: los
      POSTGRES_PASSWORD: los
    ports: ["5432:5432"]
    volumes: ["./data/pg:/var/lib/postgresql/data"]
```

- [ ] **Step 7: Generate wrapper + verify modules resolve**

Run: `gradle wrapper --gradle-version 8.10` (or use existing) then `./gradlew projects`
Expected: lists `platform`, `app`, `loan-core`, `parties`.

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "chore: scaffold Gradle multi-module Spring Boot project"
```

---

## Task 1: platform — error model + response envelopes + exception handler

> Invoke `anthropic-skills:java-skills` first to confirm conventions.

**Files:** Create `error/DomainException.java`, `NotFoundException.java`, `ConflictException.java`, `ForbiddenException.java`; `web/ApiResponse.java`, `ApiError.java`, `PagedResponse.java`, `GlobalExceptionHandler.java`. **Test:** `web/GlobalExceptionHandlerTest.java`.

- [ ] **Step 1: Write failing test** — `GlobalExceptionHandlerTest.java`

```java
package com.msfg.los.platform.web;

import com.msfg.los.platform.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsDomainExceptionToStatusAndCode() {
        ResponseEntity<ApiError> resp = handler.handleDomain(new NotFoundException("Loan", "abc"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(resp.getBody().message()).contains("abc");
    }
}
```

- [ ] **Step 2: Run, verify it fails** — `./gradlew :platform:test --tests "*GlobalExceptionHandlerTest"` → FAIL (classes missing).

- [ ] **Step 3: Implement**

`error/DomainException.java`:
```java
package com.msfg.los.platform.error;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public DomainException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
}
```

`error/NotFoundException.java`:
```java
package com.msfg.los.platform.error;
import org.springframework.http.HttpStatus;

public class NotFoundException extends DomainException {
    public NotFoundException(String entity, Object id) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", entity + " not found: " + id);
    }
}
```

`error/ConflictException.java`:
```java
package com.msfg.los.platform.error;
import org.springframework.http.HttpStatus;

public class ConflictException extends DomainException {
    public ConflictException(String message) { super(HttpStatus.CONFLICT, "CONFLICT", message); }
}
```

`error/ForbiddenException.java`:
```java
package com.msfg.los.platform.error;
import org.springframework.http.HttpStatus;

public class ForbiddenException extends DomainException {
    public ForbiddenException(String message) { super(HttpStatus.FORBIDDEN, "FORBIDDEN", message); }
}
```

`web/ApiResponse.java`:
```java
package com.msfg.los.platform.web;

public record ApiResponse<T>(boolean success, T data) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(true, data); }
}
```

`web/ApiError.java`:
```java
package com.msfg.los.platform.web;

import java.time.Instant;
import java.util.Map;

public record ApiError(boolean success, String code, String message, Map<String, String> fields, Instant timestamp) {
    public static ApiError of(String code, String message, Map<String, String> fields, Instant ts) {
        return new ApiError(false, code, message, fields, ts);
    }
}
```

`web/PagedResponse.java`:
```java
package com.msfg.los.platform.web;

import org.springframework.data.domain.Page;
import java.util.List;

public record PagedResponse<T>(List<T> items, int page, int size, long total, int totalPages) {
    public static <T> PagedResponse<T> from(Page<T> p) {
        return new PagedResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
```

`web/GlobalExceptionHandler.java`:
```java
package com.msfg.los.platform.web;

import com.msfg.los.platform.error.DomainException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException ex) {
        return ResponseEntity.status(ex.status())
            .body(ApiError.of(ex.code(), ex.getMessage(), Map.of(), Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.badRequest()
            .body(ApiError.of("VALIDATION_ERROR", "Validation failed", fields, Instant.now()));
    }
}
```

- [ ] **Step 4: Run, verify pass** — `./gradlew :platform:test --tests "*GlobalExceptionHandlerTest"` → PASS.

- [ ] **Step 5: Commit** — `git commit -am "feat(platform): error model + response envelopes + exception handler"`

---

## Task 2: platform — base + auditable entities

**Files:** Create `domain/BaseEntity.java`, `domain/AuditableEntity.java`. (No standalone test; exercised by integration tests in later tasks.)

- [ ] **Step 1: Implement `BaseEntity`**

```java
package com.msfg.los.platform.domain;

import jakarta.persistence.*;
import java.util.Objects;
import java.util.UUID;

@MappedSuperclass
public abstract class BaseEntity {
    @Id @GeneratedValue private UUID id;
    @Version private Long version;

    public UUID getId() { return id; }
    public Long getVersion() { return version; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BaseEntity that)) return false;
        return id != null && id.equals(that.id);
    }
    @Override public int hashCode() { return Objects.hash(getClass()); }
}
```

- [ ] **Step 2: Implement `AuditableEntity`**

```java
package com.msfg.los.platform.domain;

import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity extends BaseEntity {
    @CreatedDate private Instant createdAt;
    @CreatedBy private String createdBy;
    @LastModifiedDate private Instant updatedAt;
    @LastModifiedBy private String updatedBy;

    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
```

- [ ] **Step 3: Compile** — `./gradlew :platform:compileJava` → SUCCESS.
- [ ] **Step 4: Commit** — `git commit -am "feat(platform): base + auditable JPA entities"`

---

## Task 3: platform — NPI encryption (AES-256-GCM)

**Files:** Create `crypto/NpiCipher.java`, `crypto/EncryptedStringConverter.java`. **Test:** `crypto/NpiCipherTest.java`.

- [ ] **Step 1: Failing test** — `NpiCipherTest.java`

```java
package com.msfg.los.platform.crypto;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NpiCipherTest {
    // 32-byte base64 key for AES-256
    private final NpiCipher cipher = new NpiCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");

    @Test void roundTrips() {
        String ct = cipher.encrypt("123-45-6789");
        assertThat(ct).isNotEqualTo("123-45-6789");
        assertThat(cipher.decrypt(ct)).isEqualTo("123-45-6789");
    }

    @Test void producesDifferentCiphertextEachTime() {
        assertThat(cipher.encrypt("secret")).isNotEqualTo(cipher.encrypt("secret"));
    }

    @Test void rejectsTamperedCiphertext() {
        String ct = cipher.encrypt("secret");
        String tampered = ct.substring(0, ct.length() - 2) + (ct.endsWith("A") ? "B" : "A") + "=";
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(RuntimeException.class);
    }
}
```

- [ ] **Step 2: Run, verify fail** — `./gradlew :platform:test --tests "*NpiCipherTest"` → FAIL.

- [ ] **Step 3: Implement `NpiCipher`**

```java
package com.msfg.los.platform.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

public class NpiCipher {
    private static final int IV_LEN = 12;       // 96-bit nonce for GCM
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public NpiCipher(String base64Key) {
        this.key = new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
    }

    public String encrypt(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) { throw new IllegalStateException("NPI encrypt failed", e); }
    }

    public String decrypt(String stored) {
        if (stored == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            ByteBuffer buf = ByteBuffer.wrap(all);
            byte[] iv = new byte[IV_LEN]; buf.get(iv);
            byte[] ct = new byte[buf.remaining()]; buf.get(ct);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("NPI decrypt failed", e); }
    }
}
```

- [ ] **Step 4: Implement `EncryptedStringConverter`** (Spring-managed so it gets the cipher; `autoApply=false` — apply per-column in Spec 2)

```java
package com.msfg.los.platform.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Converter(autoApply = false)
public class EncryptedStringConverter implements AttributeConverter<String, String> {
    @Autowired private NpiCipher cipher;   // wired by Spring's JPA converter support
    @Override public String convertToDatabaseColumn(String attribute) { return cipher.encrypt(attribute); }
    @Override public String convertToEntityAttribute(String dbData) { return cipher.decrypt(dbData); }
}
```

- [ ] **Step 5: Run, verify pass** — `./gradlew :platform:test --tests "*NpiCipherTest"` → PASS.
- [ ] **Step 6: Commit** — `git commit -am "feat(platform): AES-256-GCM NPI cipher + JPA converter"`

---

## Task 4: platform — security primitives + loan-number generator

**Files:** Create `security/Role.java`, `security/CurrentUser.java`, `id/LoanNumberGenerator.java`. **Test:** `id/LoanNumberGeneratorTest.java`.

- [ ] **Step 1: Implement `Role`**

```java
package com.msfg.los.platform.security;

public enum Role { LO, PROCESSOR, UNDERWRITER, CLOSER, ADMIN;
    public String authority() { return "ROLE_" + name(); }
}
```

- [ ] **Step 2: Implement `CurrentUser`** (reads the validated JWT from the security context)

```java
package com.msfg.los.platform.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CurrentUser {
    public Optional<String> id() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            Jwt token = jwt.getToken();
            return Optional.ofNullable(token.getSubject());
        }
        return Optional.empty();
    }
    public Set<String> roles() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return Set.of();
        return auth.getAuthorities().stream().map(Object::toString).collect(Collectors.toSet());
    }
    public boolean isAdmin() { return roles().contains(Role.ADMIN.authority()); }
}
```

- [ ] **Step 3: Failing test** — `LoanNumberGeneratorTest.java`

```java
package com.msfg.los.platform.id;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LoanNumberGeneratorTest {
    @Test void formatsZeroPadded10Digits() {
        LoanNumberGenerator gen = seq -> String.format("%010d", seq);
        assertThat(gen.format(42L)).isEqualTo("0000000042");
        assertThat(gen.format(42L)).hasSize(10);
    }
}
```

- [ ] **Step 4: Implement `LoanNumberGenerator`** (interface; the sequence-backed impl lives in loan-core Task 7 where the JPA sequence exists)

```java
package com.msfg.los.platform.id;

public interface LoanNumberGenerator {
    /** Format a monotonic sequence value into a human-facing loan number. */
    String format(long sequenceValue);
}
```

- [ ] **Step 5: Run, verify pass** — `./gradlew :platform:test --tests "*LoanNumberGeneratorTest"` → PASS.
- [ ] **Step 6: Commit** — `git commit -am "feat(platform): Role, CurrentUser, LoanNumberGenerator"`

---

## Task 5: app — bootstrap, config, profiles, Testcontainers base (context loads)

**Files:** Create `LosApplication.java`, `config/JpaAuditingConfig.java`, `config/OpenApiConfig.java`, `application*.yml`, `support/AbstractIntegrationTest.java`, `LosApplicationTests.java`, `Dockerfile`. (SecurityConfig in Task 6.)

- [ ] **Step 1: `LosApplication.java`** (scans all module packages)

```java
package com.msfg.los;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.msfg.los")
public class LosApplication {
    public static void main(String[] args) { SpringApplication.run(LosApplication.class, args); }
}
```

> Entities live in module packages; add `@EntityScan("com.msfg.los")` and `@EnableJpaRepositories("com.msfg.los")` to `LosApplication` or a config class so JPA finds them across modules.

- [ ] **Step 2: `JpaAuditingConfig.java`** (auditor = current user id, fallback "system")

```java
package com.msfg.los.config;

import com.msfg.los.platform.security.CurrentUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
    @Bean public AuditorAware<String> auditorAware(CurrentUser currentUser) {
        return () -> Optional.of(currentUser.id().orElse("system"));
    }
}
```

- [ ] **Step 3: `OpenApiConfig.java`** (bearer auth scheme for Swagger UI)

```java
package com.msfg.los.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean public OpenAPI api() {
        return new OpenAPI()
            .info(new Info().title("MSFG LOS API").version("v1"))
            .components(new Components().addSecuritySchemes("bearer",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
```

- [ ] **Step 4: `application.yml`** (base)

```yaml
spring:
  application: { name: msfg-los }
  jpa:
    hibernate: { ddl-auto: validate }
    open-in-view: false
    properties: { hibernate.jdbc.time_zone: UTC }
  flyway: { enabled: true, locations: classpath:db/migration }
los:
  npi:
    key: ${LOS_NPI_KEY:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=}   # 32-byte base64; override in dev/prod
management:
  endpoints.web.exposure.include: health,info
springdoc:
  swagger-ui.path: /swagger-ui.html
```

> Add a `@Bean NpiCipher` in a small `config/CryptoConfig.java` reading `los.npi.key`:
> ```java
> @Configuration class CryptoConfig {
>   @Bean NpiCipher npiCipher(@Value("${los.npi.key}") String key) { return new NpiCipher(key); }
> }
> ```

- [ ] **Step 5: `application-local.yml`** (local Postgres; permissive JWT for dev — see Task 6 note)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/msfg_los
    username: los
    password: los
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${COGNITO_ISSUER:http://localhost:9999/dev-issuer}
```

`application-dev.yml` / `application-prod.yml`: same shape with real `issuer-uri` placeholders (`https://cognito-idp.<region>.amazonaws.com/<userPoolId>`).

- [ ] **Step 6: `AbstractIntegrationTest.java`** (Testcontainers Postgres via `@ServiceConnection`)

```java
package com.msfg.los.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
}
```

> Add `src/test/resources/application-test.yml` with the same `issuer-uri` placeholder so the context starts; security is exercised via `spring-security-test` post-processors, not a live IdP.

- [ ] **Step 7: Failing→passing context test** — `LosApplicationTests.java`

```java
package com.msfg.los;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

class LosApplicationTests extends AbstractIntegrationTest {
    @Test void contextLoads() {}
}
```

Run: `./gradlew :app:test --tests "*LosApplicationTests"` (Docker required). Expected: initially FAIL (no migrations yet → Flyway/JPA validate fails) — this passes after Task 7's V1 migration. Order note: if running Task 5 in isolation, temporarily set `ddl-auto: none` + `flyway.enabled: false`; revert when migrations land.

- [ ] **Step 8: `Dockerfile`** (multi-stage)

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :app:bootJar -x test --no-daemon
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
```

- [ ] **Step 9: Commit** — `git commit -am "feat(app): Spring Boot bootstrap, config, profiles, Testcontainers base"`

---

## Task 6: app — Spring Security resource server (Cognito JWT → roles)

**Files:** Create `config/SecurityConfig.java`. **Test:** `config/SecurityConfigTest.java`.

- [ ] **Step 1: Failing test** — `SecurityConfigTest.java` (uses a throwaway controller + `jwt()`)

```java
package com.msfg.los.config;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class SecurityConfigTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;

    @Test void noToken_is401() throws Exception {
        mvc.perform(get("/api/loans")).andExpect(status().isUnauthorized());
    }
    @Test void wrongRole_is403_onCreate() throws Exception {
        mvc.perform(jwt -> jwt) ; // placeholder; real call below
    }
    @Test void validJwtMapsGroupsToRoles() throws Exception {
        mvc.perform(get("/api/loans")
                .with(jwt().jwt(j -> j.claim("cognito:groups", java.util.List.of("LO")))))
            .andExpect(status().isOk());
    }
    @Test void actuatorHealthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
```

> Note: `GET /api/loans` exists after Task 9. If executing Task 6 standalone, point the test at a temporary `@RestController` returning 200; replace once the loan controller lands. Keep the 401 + actuator-public assertions regardless.

- [ ] **Step 2: Run, verify fail** — `./gradlew :app:test --tests "*SecurityConfigTest"` → FAIL.

- [ ] **Step 3: Implement `SecurityConfig`** (maps `cognito:groups` → `ROLE_*`)

```java
package com.msfg.los.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.*;
import java.util.stream.Collectors;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(reg -> reg
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/loans").hasAnyRole("LO", "ADMIN")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter())));
        return http.build();
    }

    private JwtAuthenticationConverter jwtConverter() {
        JwtAuthenticationConverter conv = new JwtAuthenticationConverter();
        conv.setJwtGrantedAuthoritiesConverter((Jwt jwt) -> {
            Object groups = jwt.getClaim("cognito:groups");
            Collection<GrantedAuthority> auths = new ArrayList<>();
            if (groups instanceof Collection<?> g) {
                g.forEach(role -> auths.add(new SimpleGrantedAuthority("ROLE_" + role)));
            }
            return auths;
        });
        return conv;
    }
}
```

- [ ] **Step 4: Run, verify pass** — `./gradlew :app:test --tests "*SecurityConfigTest"` → PASS.
- [ ] **Step 5: Commit** — `git commit -am "feat(app): Cognito JWT resource server + group→role mapping"`

---

## Task 7: loan-core — ULAD enums + LoanStatus

**Files:** Create `domain/LoanPurposeType.java`, `MortgageType.java`, `LienPriorityType.java`, `AmortizationType.java`, `LoanStatus.java`. (Exercised by later tests.)

- [ ] **Step 1: Implement enums** (ULAD-aligned values)

```java
package com.msfg.los.loan.domain;
public enum LoanPurposeType { PURCHASE, REFINANCE, CONSTRUCTION, OTHER }
```
```java
package com.msfg.los.loan.domain;
public enum MortgageType { CONVENTIONAL, FHA, VA, USDA_RURAL_DEVELOPMENT, OTHER }
```
```java
package com.msfg.los.loan.domain;
public enum LienPriorityType { FIRST_LIEN, SECOND_LIEN }
```
```java
package com.msfg.los.loan.domain;
public enum AmortizationType { FIXED, ADJUSTABLE_RATE, OTHER }
```

- [ ] **Step 2: Implement `LoanStatus`**

```java
package com.msfg.los.loan.domain;

public enum LoanStatus {
    STARTED, APPLICATION_IN_PROGRESS, SUBMITTED, IN_UNDERWRITING,
    APPROVED_WITH_CONDITIONS, CLEAR_TO_CLOSE, CLOSING, FUNDED,
    WITHDRAWN, CANCELLED, DENIED, SUSPENDED;

    public boolean isTerminal() {
        return this == FUNDED || this == WITHDRAWN || this == CANCELLED || this == DENIED;
    }
}
```

- [ ] **Step 3: Compile** — `./gradlew :loan-core:compileJava` → SUCCESS.
- [ ] **Step 4: Commit** — `git commit -am "feat(loan-core): ULAD enums + LoanStatus"`

---

## Task 8: loan-core — loan lifecycle transition guard (TDD, pure domain)

**Files:** Create `domain/LoanLifecycle.java`. **Test:** `domain/LoanLifecycleTest.java`. This is pure logic — no Spring, fast unit tests.

- [ ] **Step 1: Failing test** — `LoanLifecycleTest.java`

```java
package com.msfg.los.loan.domain;

import com.msfg.los.platform.error.ConflictException;
import com.msfg.los.platform.error.ForbiddenException;
import com.msfg.los.platform.security.Role;
import org.junit.jupiter.api.Test;

import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static com.msfg.los.loan.domain.LoanStatus.*;

class LoanLifecycleTest {
    private final LoanLifecycle lifecycle = new LoanLifecycle();

    @Test void allowsLegalForwardMove() {
        assertThatCode(() -> lifecycle.assertTransition(STARTED, APPLICATION_IN_PROGRESS, Set.of(Role.LO.authority())))
            .doesNotThrowAnyException();
    }

    @Test void rejectsIllegalMove() {
        assertThatThrownBy(() -> lifecycle.assertTransition(STARTED, FUNDED, Set.of(Role.ADMIN.authority())))
            .isInstanceOf(ConflictException.class);
    }

    @Test void rejectsMoveFromTerminalState() {
        assertThatThrownBy(() -> lifecycle.assertTransition(FUNDED, CLOSING, Set.of(Role.ADMIN.authority())))
            .isInstanceOf(ConflictException.class);
    }

    @Test void enforcesRoleGate_underwriterOnlyApproves() {
        assertThatThrownBy(() -> lifecycle.assertTransition(IN_UNDERWRITING, APPROVED_WITH_CONDITIONS, Set.of(Role.LO.authority())))
            .isInstanceOf(ForbiddenException.class);
        assertThatCode(() -> lifecycle.assertTransition(IN_UNDERWRITING, APPROVED_WITH_CONDITIONS, Set.of(Role.UNDERWRITER.authority())))
            .doesNotThrowAnyException();
    }

    @Test void anyActiveStateCanWithdrawOrCancel() {
        assertThatCode(() -> lifecycle.assertTransition(SUBMITTED, WITHDRAWN, Set.of(Role.LO.authority())))
            .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run, verify fail** — `./gradlew :loan-core:test --tests "*LoanLifecycleTest"` → FAIL.

- [ ] **Step 3: Implement `LoanLifecycle`** (transition table + role requirements)

```java
package com.msfg.los.loan.domain;

import com.msfg.los.platform.error.ConflictException;
import com.msfg.los.platform.error.ForbiddenException;
import com.msfg.los.platform.security.Role;
import org.springframework.stereotype.Component;

import java.util.*;
import static com.msfg.los.loan.domain.LoanStatus.*;

@Component
public class LoanLifecycle {

    // legal forward transitions
    private static final Map<LoanStatus, Set<LoanStatus>> FORWARD = Map.of(
        STARTED, Set.of(APPLICATION_IN_PROGRESS),
        APPLICATION_IN_PROGRESS, Set.of(SUBMITTED),
        SUBMITTED, Set.of(IN_UNDERWRITING),
        IN_UNDERWRITING, Set.of(APPROVED_WITH_CONDITIONS, DENIED, SUSPENDED),
        APPROVED_WITH_CONDITIONS, Set.of(CLEAR_TO_CLOSE),
        CLEAR_TO_CLOSE, Set.of(CLOSING),
        CLOSING, Set.of(FUNDED)
    );

    // any non-terminal state may be withdrawn or cancelled
    private static final Set<LoanStatus> CANCELLABLE = Set.of(WITHDRAWN, CANCELLED);

    // role required to ENTER a given status (absent = any authenticated role)
    private static final Map<LoanStatus, Role> ENTRY_ROLE = Map.of(
        APPROVED_WITH_CONDITIONS, Role.UNDERWRITER,
        DENIED, Role.UNDERWRITER,
        SUSPENDED, Role.UNDERWRITER,
        CLEAR_TO_CLOSE, Role.UNDERWRITER,
        FUNDED, Role.CLOSER
    );

    public void assertTransition(LoanStatus from, LoanStatus to, Set<String> authorities) {
        if (from == to) throw new ConflictException("Loan already in status " + to);
        boolean legal = (FORWARD.getOrDefault(from, Set.of()).contains(to))
            || (!from.isTerminal() && CANCELLABLE.contains(to));
        if (!legal) throw new ConflictException("Illegal transition " + from + " → " + to);

        Role required = ENTRY_ROLE.get(to);
        if (required != null
            && !authorities.contains(required.authority())
            && !authorities.contains(Role.ADMIN.authority())) {
            throw new ForbiddenException("Transition to " + to + " requires role " + required);
        }
    }
}
```

- [ ] **Step 4: Run, verify pass** — `./gradlew :loan-core:test --tests "*LoanLifecycleTest"` → PASS.
- [ ] **Step 5: Commit** — `git commit -am "feat(loan-core): loan lifecycle transition guard with role gating"`

---

## Task 9: loan-core — entities + repos + V1 migration + loan-number impl

**Files:** Create `domain/Loan.java`, `SubjectProperty.java`, `LoanStatusHistory.java`; `repo/LoanRepository.java`, `LoanStatusHistoryRepository.java`; `id/SequenceLoanNumberGenerator` (in loan-core); `app/src/main/resources/db/migration/V1__loan_core.sql`. **Test:** persistence smoke inside `LoanServiceIT` (Task 10).

- [ ] **Step 1: `SubjectProperty.java`** (`@Embeddable`)

```java
package com.msfg.los.loan.domain;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

@Embeddable
public class SubjectProperty {
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;
    private BigDecimal estimatedValue;
    // Lombok-free getters/setters (or @Getter @Setter)
    public String getAddressLine1() { return addressLine1; }
    public void setAddressLine1(String v) { this.addressLine1 = v; }
    public String getCity() { return city; }
    public void setCity(String v) { this.city = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String v) { this.postalCode = v; }
    public BigDecimal getEstimatedValue() { return estimatedValue; }
    public void setEstimatedValue(BigDecimal v) { this.estimatedValue = v; }
    public String getAddressLine2() { return addressLine2; }
    public void setAddressLine2(String v) { this.addressLine2 = v; }
}
```

- [ ] **Step 2: `Loan.java`** (aggregate root; `@Getter/@Setter` Lombok)

```java
package com.msfg.los.loan.domain;

import com.msfg.los.platform.domain.AuditableEntity;
import jakarta.persistence.*;
import lombok.Getter; import lombok.Setter;
import java.math.BigDecimal;
import java.util.UUID;

@Entity @Table(name = "loan")
@Getter @Setter
public class Loan extends AuditableEntity {
    @Column(nullable = false, unique = true, updatable = false)
    private String loanNumber;

    @Column(nullable = false)
    private UUID loanOfficerId;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private LoanStatus status = LoanStatus.STARTED;

    @Enumerated(EnumType.STRING)
    private LoanPurposeType loanPurpose;
    @Enumerated(EnumType.STRING)
    private MortgageType mortgageType;
    @Enumerated(EnumType.STRING)
    private LienPriorityType lienPriority;
    @Enumerated(EnumType.STRING)
    private AmortizationType amortizationType;

    private BigDecimal noteAmount;

    @Embedded
    private SubjectProperty subjectProperty = new SubjectProperty();
}
```

- [ ] **Step 3: `LoanStatusHistory.java`**

```java
package com.msfg.los.loan.domain;

import com.msfg.los.platform.domain.AuditableEntity;
import jakarta.persistence.*;
import lombok.Getter; import lombok.Setter;
import java.util.UUID;

@Entity @Table(name = "loan_status_history")
@Getter @Setter
public class LoanStatusHistory extends AuditableEntity {
    @Column(nullable = false) private UUID loanId;
    @Enumerated(EnumType.STRING) private LoanStatus fromStatus;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private LoanStatus toStatus;
    @Column(length = 1000) private String reason;
}
```

- [ ] **Step 4: Repos**

```java
package com.msfg.los.loan.repo;
import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.domain.LoanStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface LoanRepository extends JpaRepository<Loan, UUID> {
    Optional<Loan> findByLoanNumber(String loanNumber);
    Page<Loan> findByLoanOfficerId(UUID loanOfficerId, Pageable pageable);
    Page<Loan> findByLoanOfficerIdAndStatus(UUID loanOfficerId, LoanStatus status, Pageable pageable);
    Page<Loan> findByStatus(LoanStatus status, Pageable pageable);
}
```
```java
package com.msfg.los.loan.repo;
import com.msfg.los.loan.domain.LoanStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface LoanStatusHistoryRepository extends JpaRepository<LoanStatusHistory, UUID> {
    List<LoanStatusHistory> findByLoanIdOrderByCreatedAtAsc(UUID loanId);
}
```

- [ ] **Step 5: `SequenceLoanNumberGenerator`** (DB sequence-backed; format `1` + 9-digit padded)

```java
package com.msfg.los.loan.id;

import com.msfg.los.platform.id.LoanNumberGenerator;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

@Component
public class SequenceLoanNumberGenerator implements LoanNumberGenerator {
    private final EntityManager em;
    public SequenceLoanNumberGenerator(EntityManager em) { this.em = em; }

    @Override public String format(long sequenceValue) { return String.format("1%09d", sequenceValue); }

    public String next() {
        Number val = (Number) em.createNativeQuery("select nextval('loan_number_seq')").getSingleResult();
        return format(val.longValue());
    }
}
```

- [ ] **Step 6: `V1__loan_core.sql`**

```sql
create sequence loan_number_seq start 1 increment 1;

create table loan (
    id uuid primary key,
    version bigint not null default 0,
    loan_number varchar(20) not null unique,
    loan_officer_id uuid not null,
    status varchar(40) not null,
    loan_purpose varchar(40),
    mortgage_type varchar(40),
    lien_priority varchar(40),
    amortization_type varchar(40),
    note_amount numeric(15,2),
    address_line1 varchar(255),
    address_line2 varchar(255),
    city varchar(120),
    state varchar(2),
    postal_code varchar(10),
    estimated_value numeric(15,2),
    created_at timestamptz,
    created_by varchar(120),
    updated_at timestamptz,
    updated_by varchar(120)
);
create index idx_loan_officer on loan(loan_officer_id);
create index idx_loan_status on loan(status);

create table loan_status_history (
    id uuid primary key,
    version bigint not null default 0,
    loan_id uuid not null references loan(id),
    from_status varchar(40),
    to_status varchar(40) not null,
    reason varchar(1000),
    created_at timestamptz,
    created_by varchar(120),
    updated_at timestamptz,
    updated_by varchar(120)
);
create index idx_history_loan on loan_status_history(loan_id);
```

- [ ] **Step 7: Verify schema validates** — run `./gradlew :app:test --tests "*LosApplicationTests"` (Flyway migrates the Testcontainer, JPA `validate` passes) → PASS.
- [ ] **Step 8: Commit** — `git commit -am "feat(loan-core): Loan/SubjectProperty/StatusHistory entities + V1 migration"`

---

## Task 10: loan-core — LoanService + LoanAccessGuard (Testcontainers IT)

**Files:** Create `service/LoanService.java`, `service/LoanAccessGuard.java`, DTOs (`web/dto/*`). **Test:** `service/LoanServiceIT.java`.

- [ ] **Step 1: DTOs** (records)

```java
package com.msfg.los.loan.web.dto;
import com.msfg.los.loan.domain.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateLoanRequest(
    @NotNull LoanPurposeType loanPurpose, MortgageType mortgageType,
    LienPriorityType lienPriority, AmortizationType amortizationType,
    BigDecimal noteAmount, UUID loanOfficerId) {}
```
```java
package com.msfg.los.loan.web.dto;
import com.msfg.los.loan.domain.*;
import java.math.BigDecimal;
public record UpdateLoanRequest(
    MortgageType mortgageType, LienPriorityType lienPriority,
    AmortizationType amortizationType, BigDecimal noteAmount,
    String addressLine1, String addressLine2, String city, String state,
    String postalCode, BigDecimal estimatedValue) {}
```
```java
package com.msfg.los.loan.web.dto;
import com.msfg.los.loan.domain.LoanStatus;
import jakarta.validation.constraints.NotNull;
public record TransitionRequest(@NotNull LoanStatus targetStatus, String reason) {}
```
```java
package com.msfg.los.loan.web.dto;
import com.msfg.los.loan.domain.*;
import java.math.BigDecimal; import java.util.UUID;
public record LoanSummaryResponse(UUID id, String loanNumber, LoanStatus status,
    LoanPurposeType loanPurpose, MortgageType mortgageType, BigDecimal noteAmount,
    UUID loanOfficerId, String propertyCity, String propertyState) {
    public static LoanSummaryResponse from(Loan l) {
        return new LoanSummaryResponse(l.getId(), l.getLoanNumber(), l.getStatus(),
            l.getLoanPurpose(), l.getMortgageType(), l.getNoteAmount(), l.getLoanOfficerId(),
            l.getSubjectProperty().getCity(), l.getSubjectProperty().getState());
    }
}
```
```java
package com.msfg.los.loan.web.dto;
import com.msfg.los.loan.domain.*;
import java.util.UUID;
public record LoanListItemResponse(UUID id, String loanNumber, LoanStatus status, UUID loanOfficerId) {
    public static LoanListItemResponse from(Loan l) {
        return new LoanListItemResponse(l.getId(), l.getLoanNumber(), l.getStatus(), l.getLoanOfficerId());
    }
}
```

- [ ] **Step 2: `LoanAccessGuard`**

```java
package com.msfg.los.loan.service;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.platform.error.ForbiddenException;
import com.msfg.los.platform.security.CurrentUser;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class LoanAccessGuard {
    private final CurrentUser currentUser;
    public LoanAccessGuard(CurrentUser currentUser) { this.currentUser = currentUser; }

    public void assertCanAccess(Loan loan) {
        if (currentUser.isAdmin()) return;
        String me = currentUser.id().orElse(null);
        if (me == null || !loan.getLoanOfficerId().equals(UUID.fromString(me))) {
            throw new ForbiddenException("No access to loan " + loan.getLoanNumber());
        }
    }
}
```

> Note: this assumes the Cognito `sub` is a UUID. If the pool uses non-UUID subjects, store `loanOfficerId` as `String` instead. Confirm at execution; adjust the column + field type together.

- [ ] **Step 3: Failing test** — `LoanServiceIT.java`

```java
package com.msfg.los.loan.service;

import com.msfg.los.loan.domain.*;
import com.msfg.los.loan.web.dto.*;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanServiceIT extends AbstractIntegrationTest {
    @Autowired LoanService service;
    static final UUID LO = UUID.randomUUID();

    @Test void createsLoanWithGeneratedNumberAndStartedStatus() {
        var req = new CreateLoanRequest(LoanPurposeType.PURCHASE, MortgageType.CONVENTIONAL,
            LienPriorityType.FIRST_LIEN, AmortizationType.FIXED, null, LO);
        Loan loan = service.create(req);
        assertThat(loan.getLoanNumber()).startsWith("1").hasSize(10);
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.STARTED);
    }

    @Test void transitionWritesHistoryRow() {
        var loan = service.create(new CreateLoanRequest(LoanPurposeType.PURCHASE, null, null, null, null, LO));
        service.transition(loan.getId(), new TransitionRequest(LoanStatus.APPLICATION_IN_PROGRESS, "start app"),
            Set.of("ROLE_LO"), LO.toString());
        var history = service.history(loan.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getToStatus()).isEqualTo(LoanStatus.APPLICATION_IN_PROGRESS);
    }

    @Test void illegalTransitionRejected() {
        var loan = service.create(new CreateLoanRequest(LoanPurposeType.PURCHASE, null, null, null, null, LO));
        assertThatThrownBy(() -> service.transition(loan.getId(),
            new TransitionRequest(LoanStatus.FUNDED, "x"), Set.of("ROLE_ADMIN"), LO.toString()))
            .isInstanceOf(com.msfg.los.platform.error.ConflictException.class);
    }
}
```

- [ ] **Step 4: Run, verify fail** — `./gradlew :loan-core:test --tests "*LoanServiceIT"` (needs Postgres on classpath — run from `:app` if loan-core lacks the driver; see note). FAIL.

> **Test wiring note:** integration tests that boot Spring + Postgres should live where the runtime deps exist. Simplest: place `LoanServiceIT`/`LoanControllerIT`/borrower ITs under **`app/src/test`** (app has the Postgres driver, security-test, testcontainers). Keep pure-domain unit tests (`LoanLifecycleTest`, `NpiCipherTest`) in their own module. Update the File Structure accordingly during execution.

- [ ] **Step 5: Implement `LoanService`**

```java
package com.msfg.los.loan.service;

import com.msfg.los.loan.domain.*;
import com.msfg.los.loan.id.SequenceLoanNumberGenerator;
import com.msfg.los.loan.repo.*;
import com.msfg.los.loan.web.dto.*;
import com.msfg.los.platform.error.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class LoanService {
    private final LoanRepository loans;
    private final LoanStatusHistoryRepository histories;
    private final SequenceLoanNumberGenerator numberGen;
    private final LoanLifecycle lifecycle;

    public LoanService(LoanRepository loans, LoanStatusHistoryRepository histories,
                       SequenceLoanNumberGenerator numberGen, LoanLifecycle lifecycle) {
        this.loans = loans; this.histories = histories; this.numberGen = numberGen; this.lifecycle = lifecycle;
    }

    @Transactional
    public Loan create(CreateLoanRequest req) {
        Loan loan = new Loan();
        loan.setLoanNumber(numberGen.next());
        loan.setLoanOfficerId(req.loanOfficerId());
        loan.setStatus(LoanStatus.STARTED);
        loan.setLoanPurpose(req.loanPurpose());
        loan.setMortgageType(req.mortgageType());
        loan.setLienPriority(req.lienPriority());
        loan.setAmortizationType(req.amortizationType());
        loan.setNoteAmount(req.noteAmount());
        return loans.save(loan);
    }

    @Transactional(readOnly = true)
    public Loan get(UUID id) {
        return loans.findById(id).orElseThrow(() -> new NotFoundException("Loan", id));
    }

    @Transactional(readOnly = true)
    public Page<Loan> pipeline(UUID loanOfficerId, LoanStatus status, boolean admin, Pageable pageable) {
        if (admin) return status == null ? loans.findAll(pageable) : loans.findByStatus(status, pageable);
        return status == null
            ? loans.findByLoanOfficerId(loanOfficerId, pageable)
            : loans.findByLoanOfficerIdAndStatus(loanOfficerId, status, pageable);
    }

    @Transactional
    public Loan update(UUID id, UpdateLoanRequest req) {
        Loan loan = get(id);
        if (req.mortgageType() != null) loan.setMortgageType(req.mortgageType());
        if (req.lienPriority() != null) loan.setLienPriority(req.lienPriority());
        if (req.amortizationType() != null) loan.setAmortizationType(req.amortizationType());
        if (req.noteAmount() != null) loan.setNoteAmount(req.noteAmount());
        SubjectProperty p = loan.getSubjectProperty();
        if (req.addressLine1() != null) p.setAddressLine1(req.addressLine1());
        if (req.addressLine2() != null) p.setAddressLine2(req.addressLine2());
        if (req.city() != null) p.setCity(req.city());
        if (req.state() != null) p.setState(req.state());
        if (req.postalCode() != null) p.setPostalCode(req.postalCode());
        if (req.estimatedValue() != null) p.setEstimatedValue(req.estimatedValue());
        return loan;
    }

    @Transactional
    public Loan transition(UUID id, TransitionRequest req, Set<String> authorities, String actorId) {
        Loan loan = get(id);
        LoanStatus from = loan.getStatus();
        lifecycle.assertTransition(from, req.targetStatus(), authorities);
        loan.setStatus(req.targetStatus());
        LoanStatusHistory h = new LoanStatusHistory();
        h.setLoanId(loan.getId());
        h.setFromStatus(from);
        h.setToStatus(req.targetStatus());
        h.setReason(req.reason());
        histories.save(h);
        return loan;
    }

    @Transactional(readOnly = true)
    public List<LoanStatusHistory> history(UUID loanId) {
        return histories.findByLoanIdOrderByCreatedAtAsc(loanId);
    }
}
```

- [ ] **Step 6: Run, verify pass** — `./gradlew :app:test --tests "*LoanServiceIT"` → PASS.
- [ ] **Step 7: Commit** — `git commit -am "feat(loan-core): LoanService + access guard + DTOs"`

---

## Task 11: loan-core — LoanController (REST) + MockMvc API tests

**Files:** Create `web/LoanController.java`. **Test:** `web/LoanControllerIT.java` (in `app/src/test`).

- [ ] **Step 1: Failing test** — `LoanControllerIT.java`

```java
package com.msfg.los.loan.web;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class LoanControllerIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    static final String LO = UUID.randomUUID().toString();

    private org.springframework.test.web.servlet.request.RequestPostProcessor loUser() {
        return jwt().jwt(j -> j.subject(LO).claim("cognito:groups", List.of("LO")));
    }

    @Test void createThenFetchSummary() throws Exception {
        String body = """
            {"loanPurpose":"PURCHASE","mortgageType":"CONVENTIONAL","loanOfficerId":"%s"}
            """.formatted(LO);
        var res = mvc.perform(post("/api/loans").with(loUser())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.loanNumber").exists())
            .andExpect(jsonPath("$.data.status").value("STARTED"))
            .andReturn();
        // pipeline list shows it
        mvc.perform(get("/api/loans").with(loUser()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test void noToken401() throws Exception {
        mvc.perform(get("/api/loans")).andExpect(status().isUnauthorized());
    }

    @Test void processorCannotCreate403() throws Exception {
        mvc.perform(post("/api/loans")
                .with(jwt().jwt(j -> j.claim("cognito:groups", List.of("PROCESSOR"))))
                .contentType(MediaType.APPLICATION_JSON).content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
            .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run, verify fail** — `./gradlew :app:test --tests "*LoanControllerIT"` → FAIL.

- [ ] **Step 3: Implement `LoanController`**

```java
package com.msfg.los.loan.web;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.domain.LoanStatus;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.loan.web.dto.*;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.web.ApiResponse;
import com.msfg.los.platform.web.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/loans")
public class LoanController {
    private final LoanService service;
    private final LoanAccessGuard accessGuard;
    private final CurrentUser currentUser;

    public LoanController(LoanService service, LoanAccessGuard accessGuard, CurrentUser currentUser) {
        this.service = service; this.accessGuard = accessGuard; this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<LoanSummaryResponse>> create(@Valid @RequestBody CreateLoanRequest req) {
        Loan loan = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(LoanSummaryResponse.from(loan)));
    }

    @GetMapping
    public ApiResponse<PagedResponse<LoanListItemResponse>> pipeline(
            @RequestParam(required = false) LoanStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID me = currentUser.id().map(UUID::fromString).orElse(null);
        Page<Loan> result = service.pipeline(me, status, currentUser.isAdmin(), PageRequest.of(page, size));
        return ApiResponse.ok(PagedResponse.from(result.map(LoanListItemResponse::from)));
    }

    @GetMapping("/{id}")
    public ApiResponse<LoanSummaryResponse> get(@PathVariable UUID id) {
        Loan loan = service.get(id);
        accessGuard.assertCanAccess(loan);
        return ApiResponse.ok(LoanSummaryResponse.from(loan));
    }

    @PatchMapping("/{id}")
    public ApiResponse<LoanSummaryResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateLoanRequest req) {
        accessGuard.assertCanAccess(service.get(id));
        return ApiResponse.ok(LoanSummaryResponse.from(service.update(id, req)));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<LoanSummaryResponse> transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest req) {
        Loan loan = service.get(id);
        accessGuard.assertCanAccess(loan);
        Loan updated = service.transition(id, req, currentUser.roles(), currentUser.id().orElse("system"));
        return ApiResponse.ok(LoanSummaryResponse.from(updated));
    }
}
```

- [ ] **Step 4: Run, verify pass** — `./gradlew :app:test --tests "*LoanControllerIT"` → PASS.
- [ ] **Step 5: Commit** — `git commit -am "feat(loan-core): Loan REST controller + API tests"`

---

## Task 12: parties — BorrowerParty (entity + service + controller + V2 migration)

**Files:** Create `domain/BorrowerParty.java`, `repo/BorrowerRepository.java`, `service/BorrowerService.java`, `web/BorrowerController.java`, DTOs; `app/.../db/migration/V2__parties.sql`. **Test:** `BorrowerControllerIT.java` (in `app/src/test`).

- [ ] **Step 1: `BorrowerParty.java`**

```java
package com.msfg.los.parties.domain;

import com.msfg.los.platform.domain.AuditableEntity;
import jakarta.persistence.*;
import lombok.Getter; import lombok.Setter;
import java.util.UUID;

@Entity @Table(name = "borrower_party")
@Getter @Setter
public class BorrowerParty extends AuditableEntity {
    @Column(nullable = false) private UUID loanId;
    @Column(nullable = false) private boolean primary;
    @Column(nullable = false) private int ordinal;
    private String firstName;
    private String lastName;
}
```

- [ ] **Step 2: `BorrowerRepository.java`**

```java
package com.msfg.los.parties.repo;
import com.msfg.los.parties.domain.BorrowerParty;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface BorrowerRepository extends JpaRepository<BorrowerParty, UUID> {
    List<BorrowerParty> findByLoanIdOrderByOrdinalAsc(UUID loanId);
    long countByLoanId(UUID loanId);
}
```

- [ ] **Step 3: DTOs**

```java
package com.msfg.los.parties.web.dto;
import jakarta.validation.constraints.NotBlank;
public record AddBorrowerRequest(@NotBlank String firstName, @NotBlank String lastName, boolean primary) {}
```
```java
package com.msfg.los.parties.web.dto;
public record UpdateBorrowerRequest(String firstName, String lastName, Boolean primary) {}
```
```java
package com.msfg.los.parties.web.dto;
import com.msfg.los.parties.domain.BorrowerParty;
import java.util.UUID;
public record BorrowerResponse(UUID id, UUID loanId, boolean primary, int ordinal, String firstName, String lastName) {
    public static BorrowerResponse from(BorrowerParty b) {
        return new BorrowerResponse(b.getId(), b.getLoanId(), b.isPrimary(), b.getOrdinal(), b.getFirstName(), b.getLastName());
    }
}
```

- [ ] **Step 4: `BorrowerService.java`** (enforces single primary; uses loan-core `LoanService` to verify loan exists + `LoanAccessGuard`)

```java
package com.msfg.los.parties.service;

import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.domain.BorrowerParty;
import com.msfg.los.parties.repo.BorrowerRepository;
import com.msfg.los.parties.web.dto.*;
import com.msfg.los.platform.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class BorrowerService {
    private final BorrowerRepository borrowers;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;

    public BorrowerService(BorrowerRepository borrowers, LoanService loanService, LoanAccessGuard accessGuard) {
        this.borrowers = borrowers; this.loanService = loanService; this.accessGuard = accessGuard;
    }

    @Transactional
    public BorrowerParty add(UUID loanId, AddBorrowerRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));   // 404 if loan missing, 403 if no access
        long count = borrowers.countByLoanId(loanId);
        BorrowerParty b = new BorrowerParty();
        b.setLoanId(loanId);
        b.setFirstName(req.firstName());
        b.setLastName(req.lastName());
        b.setOrdinal((int) count);
        b.setPrimary(req.primary() || count == 0);     // first borrower is primary by default
        if (b.isPrimary()) clearOtherPrimaries(loanId, null);
        return borrowers.save(b);
    }

    @Transactional(readOnly = true)
    public List<BorrowerParty> list(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return borrowers.findByLoanIdOrderByOrdinalAsc(loanId);
    }

    @Transactional
    public BorrowerParty update(UUID loanId, UUID borrowerId, UpdateBorrowerRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        BorrowerParty b = borrowers.findById(borrowerId)
            .filter(x -> x.getLoanId().equals(loanId))
            .orElseThrow(() -> new NotFoundException("Borrower", borrowerId));
        if (req.firstName() != null) b.setFirstName(req.firstName());
        if (req.lastName() != null) b.setLastName(req.lastName());
        if (Boolean.TRUE.equals(req.primary())) { clearOtherPrimaries(loanId, borrowerId); b.setPrimary(true); }
        return b;
    }

    @Transactional
    public void delete(UUID loanId, UUID borrowerId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        BorrowerParty b = borrowers.findById(borrowerId)
            .filter(x -> x.getLoanId().equals(loanId))
            .orElseThrow(() -> new NotFoundException("Borrower", borrowerId));
        borrowers.delete(b);
    }

    private void clearOtherPrimaries(UUID loanId, UUID exceptId) {
        borrowers.findByLoanIdOrderByOrdinalAsc(loanId).forEach(other -> {
            if (exceptId == null || !other.getId().equals(exceptId)) other.setPrimary(false);
        });
    }
}
```

- [ ] **Step 5: `BorrowerController.java`**

```java
package com.msfg.los.parties.web;

import com.msfg.los.parties.service.BorrowerService;
import com.msfg.los.parties.web.dto.*;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/borrowers")
public class BorrowerController {
    private final BorrowerService service;
    public BorrowerController(BorrowerService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ApiResponse<BorrowerResponse>> add(@PathVariable UUID loanId, @Valid @RequestBody AddBorrowerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(BorrowerResponse.from(service.add(loanId, req))));
    }
    @GetMapping
    public ApiResponse<List<BorrowerResponse>> list(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.list(loanId).stream().map(BorrowerResponse::from).toList());
    }
    @PatchMapping("/{borrowerId}")
    public ApiResponse<BorrowerResponse> update(@PathVariable UUID loanId, @PathVariable UUID borrowerId, @Valid @RequestBody UpdateBorrowerRequest req) {
        return ApiResponse.ok(BorrowerResponse.from(service.update(loanId, borrowerId, req)));
    }
    @DeleteMapping("/{borrowerId}")
    public ResponseEntity<Void> delete(@PathVariable UUID loanId, @PathVariable UUID borrowerId) {
        service.delete(loanId, borrowerId); return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 6: `V2__parties.sql`**

```sql
create table borrower_party (
    id uuid primary key,
    version bigint not null default 0,
    loan_id uuid not null references loan(id),
    is_primary boolean not null default false,
    ordinal int not null default 0,
    first_name varchar(120),
    last_name varchar(120),
    created_at timestamptz,
    created_by varchar(120),
    updated_at timestamptz,
    updated_by varchar(120)
);
create index idx_borrower_loan on borrower_party(loan_id);
```

> Map `BorrowerParty.primary` → column `is_primary` (`@Column(name="is_primary")`) since `primary` is reserved-ish; confirm at execution.

- [ ] **Step 7: Failing→passing test** — `BorrowerControllerIT.java`

```java
package com.msfg.los.parties.web;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class BorrowerControllerIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    static final String LO = UUID.randomUUID().toString();

    private org.springframework.test.web.servlet.request.RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("cognito:groups", List.of("LO")));
    }

    @Test void addBorrowerToCreatedLoan() throws Exception {
        String loanId = createLoan();
        mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Abbas\",\"lastName\":\"Hussein\",\"primary\":true}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.primary").value(true))
            .andExpect(jsonPath("$.data.ordinal").value(0));
    }

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
            .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }
}
```

- [ ] **Step 8: Run, verify pass** — `./gradlew :app:test --tests "*BorrowerControllerIT"` → PASS.
- [ ] **Step 9: Commit** — `git commit -am "feat(parties): BorrowerParty entity, service, controller + V2 migration"`

---

## Task 13: End-to-end verification + README + full build

**Files:** Create `README.md`; optional `config/LocalSeed.java` (CommandLineRunner under `local` profile).

- [ ] **Step 1: Full build green** — `./gradlew build` → all modules compile, all unit + integration tests PASS (Docker running).
- [ ] **Step 2: Boot locally**

```bash
docker compose up -d
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

- [ ] **Step 3: Manual smoke via Swagger** (`http://localhost:8080/swagger-ui.html`) — for local dev without a real IdP, generate a test JWT or temporarily relax the issuer (documented in README). Exercise:
  - `POST /api/loans` → 201, returns `loanNumber` (`1000000001`) + `STARTED`.
  - `GET /api/loans` → loan in pipeline.
  - `GET /api/loans/{id}` → summary correct.
  - `POST /api/loans/{id}/borrowers` → borrower attached, `primary=true`, `ordinal=0`.
  - `POST /api/loans/{id}/status` `{ "targetStatus":"APPLICATION_IN_PROGRESS" }` → 200; illegal target → 409; `actuator/health` → 200 unauth; no token → 401.
- [ ] **Step 4: `README.md`** — prerequisites (Java 21, Docker), `docker compose up`, `./gradlew build`, run command, Swagger URL, profile/env vars (`LOS_NPI_KEY`, `COGNITO_ISSUER`), module map, "Spec 1 done / roadmap" pointer to `docs/specs/`.
- [ ] **Step 5: Commit** — `git commit -am "docs: README + local run instructions; chore: Spec 1 foundation complete"`

---

## Self-Review

**Spec coverage** (against the approved Spec 1):
- Stack (Java21/SB3/Gradle/Postgres/Flyway) → Task 0. ✓
- Modular monolith, multi-module → Task 0 layout. ✓
- platform conventions (envelope/error/audit/pagination/IDs/NPI crypto) → Tasks 1–4. ✓
- Cognito JWT + group→role RBAC → Task 6. ✓
- ULAD enums + Loan/SubjectProperty/StatusHistory + owner field → Tasks 7, 9. ✓
- Lifecycle state machine + role gating + history rows → Tasks 8, 10. ✓
- API surface (create/list/get/patch/status/borrowers) → Tasks 11, 12. ✓
- Loan-scoped access (ADMIN all; else owner) → Task 10 `LoanAccessGuard`, applied in 11/12. ✓
- Testing (unit + Testcontainers + MockMvc + 401/403) → throughout; verification Task 13. ✓
- Docker-first → Task 0 compose + Task 5 Dockerfile. ✓
- NPI encryption established (used in Spec 2) → Task 3; converter `autoApply=false`. ✓

**Placeholder scan:** No logic placeholders. Two flagged execution-time confirmations (Cognito `sub` UUID-vs-String for `loanOfficerId`; `primary`→`is_primary` column name) are explicit decisions, not gaps. The Task 6 test has a `// placeholder` line in a draft `@Test` — **delete that stub method during execution**; the real 403 assertion is in Task 11 (`processorCannotCreate403`).

**Type consistency:** `assertTransition(from, to, Set<String> authorities)` used identically in Tasks 8 + 10. `LoanSummaryResponse.from` / `LoanListItemResponse.from` / `BorrowerResponse.from` signatures consistent. `currentUser.id()` returns `Optional<String>` everywhere. `numberGen.next()`/`format()` consistent across Tasks 4/9.

**Decisions deferred to execution (documented, not gaps):** integration tests physically live in `app/src/test` (where Postgres/security-test deps are); `loanOfficerId` type pending Cognito subject format; java-skills consulted at Task 1 to confirm conventions.
