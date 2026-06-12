package com.msfg.los.contacts.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "contact")
@Getter
@Setter
public class Contact extends TenantScopedEntity {

    @Column(nullable = false)
    private UUID loanId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContactRole role;

    @Column(nullable = false)
    private String name;

    private String company;
    private String phone;
    private String email;

    @Column(nullable = false)
    private int ordinal;
}
