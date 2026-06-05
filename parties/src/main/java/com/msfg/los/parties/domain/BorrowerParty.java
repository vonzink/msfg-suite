package com.msfg.los.parties.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "borrower_party")
@Getter
@Setter
public class BorrowerParty extends TenantScopedEntity {

    @Column(nullable = false)
    private UUID loanId;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(nullable = false)
    private int ordinal;

    private String firstName;

    private String lastName;
}
