package com.msfg.los.parties.web.dto;
import com.msfg.los.parties.domain.CitizenshipType;
import com.msfg.los.parties.domain.MaritalStatus;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.UUID;
public record AddBorrowerRequest(
    @NotBlank String firstName, @NotBlank String lastName, boolean primary,
    String middleName, String suffix, String ssn, LocalDate dateOfBirth, MaritalStatus maritalStatus,
    Integer dependentsCount, String dependentAges, CitizenshipType citizenshipType, Boolean veteran,
    Boolean unmarriedAddendumSpousalRights, UUID joinedToBorrowerId, String homePhone, String cellPhone,
    String workPhone, String workPhoneExt, String email, Boolean noEmail) {}
