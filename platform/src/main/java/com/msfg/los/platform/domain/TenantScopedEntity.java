package com.msfg.los.platform.domain;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;
import java.util.UUID;

@MappedSuperclass
public abstract class TenantScopedEntity extends AuditableEntity {
    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;
    public UUID getOrgId() { return orgId; }
}
