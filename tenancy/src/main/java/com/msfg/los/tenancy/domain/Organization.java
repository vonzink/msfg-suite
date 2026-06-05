package com.msfg.los.tenancy.domain;
import com.msfg.los.platform.domain.AuditableEntity;
import jakarta.persistence.*;
import lombok.Getter; import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.HashMap; import java.util.Map;

@Entity @Table(name = "organization")
@Getter @Setter
public class Organization extends AuditableEntity {
    @Column(nullable = false) private String name;
    @Column(nullable = false, unique = true) private String slug;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private OrgStatus status = OrgStatus.ACTIVE;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> settings = new HashMap<>();
}
