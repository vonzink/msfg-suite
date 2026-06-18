package com.msfg.los.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.TenantId;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * A tenant-scoped staff user, materialized on the first authenticated {@code /me} call.
 *
 * <p>Unlike every other domain row this entity does NOT extend {@link
 * com.msfg.los.platform.domain.TenantScopedEntity}: its primary key is the Cognito {@code sub}
 * (assigned, not generated), so {@code loan.loan_officer_id == user_account.id} is a direct logical
 * join. We therefore replicate the {@code @TenantId} org-stamping + JPA-auditing wiring locally
 * around an assigned {@code @Id}.
 */
@Entity
@Table(name = "user_account")
@EntityListeners(AuditingEntityListener.class)
public class UserAccount {

    /** Cognito sub — assigned on materialize, never auto-generated. */
    @Id
    @Column(updatable = false)
    private UUID id;

    @Version
    private Long version;

    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String email;

    private String name;

    private String initials;

    private String role;

    @CreatedDate @Column(updatable = false) private Instant createdAt;
    @CreatedBy   @Column(updatable = false) private String createdBy;
    @LastModifiedDate private Instant updatedAt;
    @LastModifiedBy   private String updatedBy;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Long getVersion() { return version; }

    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getInitials() { return initials; }
    public void setInitials(String initials) { this.initials = initials; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
