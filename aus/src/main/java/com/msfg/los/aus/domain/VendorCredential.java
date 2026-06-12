package com.msfg.los.aus.domain;

import com.msfg.los.platform.crypto.EncryptedStringConverter;
import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

// Holds vendor secrets — never add @ToString/@Data, never log, never serialize the entity directly.
@Entity
@Table(name = "vendor_credential")
@Getter
@Setter
public class VendorCredential extends TenantScopedEntity {

    /** NULL = org-wide default for the vendor; non-null = per-loan override. */
    @Column(name = "loan_id")
    private UUID loanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "vendor", nullable = false, length = 20)
    private CredentialVendor vendor;

    @Column(name = "institution_id", length = 80)
    private String institutionId;

    @Column(name = "seller_servicer_number", length = 80)
    private String sellerServicerNumber;

    @Column(name = "tpo_number", length = 80)
    private String tpoNumber;

    @Column(name = "branch_number", length = 80)
    private String branchNumber;

    @Column(name = "credit_provider_code", length = 40)
    private String creditProviderCode;

    @Column(name = "credit_affiliate_code", length = 40)
    private String creditAffiliateCode;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "username", length = 1024)
    private String username;            // AES-GCM ciphertext at rest

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "password", length = 1024)
    private String password;            // AES-GCM ciphertext at rest

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "credit_username", length = 1024)
    private String creditUsername;      // AES-GCM ciphertext at rest

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "credit_password", length = 1024)
    private String creditPassword;      // AES-GCM ciphertext at rest
}
