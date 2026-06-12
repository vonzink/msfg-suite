package com.msfg.los.aus.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

/** Per-loan AUS submission settings (DU + LPA), 1:1 per loan. */
@Entity
@Table(name = "aus_profile")
@Getter
@Setter
public class AusProfile extends TenantScopedEntity {

    @Column(name = "loan_id", nullable = false, updatable = false)
    private UUID loanId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "du_settings", nullable = false, columnDefinition = "jsonb")
    private AusVendorSettings duSettings = new AusVendorSettings(null, null, null, null, List.of());

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lpa_settings", nullable = false, columnDefinition = "jsonb")
    private AusVendorSettings lpaSettings = new AusVendorSettings(null, null, null, null, List.of());
}
