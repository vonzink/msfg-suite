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
