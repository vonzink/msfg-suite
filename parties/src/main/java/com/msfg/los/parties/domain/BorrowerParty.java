package com.msfg.los.parties.domain;

import com.msfg.los.platform.crypto.EncryptedStringConverter;
import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
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

    private String middleName;

    private String suffix;

    @Convert(converter = EncryptedStringConverter.class)
    private String ssn;                 // stored as ciphertext; holds the normalized 9 digits in memory

    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    private MaritalStatus maritalStatus;

    private Integer dependentsCount;

    private String dependentAges;

    @Enumerated(EnumType.STRING)
    private CitizenshipType citizenshipType;

    private Boolean veteran;

    private Boolean unmarriedAddendumSpousalRights;

    private UUID joinedToBorrowerId;

    private String homePhone;

    private String cellPhone;

    private String workPhone;

    private String workPhoneExt;

    private String email;

    private Boolean noEmail;
}
