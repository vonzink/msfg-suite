package com.msfg.los.declarations.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "borrower_demographics")
@Getter
@Setter
public class BorrowerDemographics extends TenantScopedEntity {

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    @Column(name = "borrower_id", nullable = false)
    private UUID borrowerId;

    @Convert(converter = EthnicitySetConverter.class)
    @Column(name = "ethnicity", length = 255)
    private Set<Ethnicity> ethnicity = new LinkedHashSet<>();

    @Convert(converter = RaceSetConverter.class)
    @Column(name = "race", length = 512)
    private Set<Race> race = new LinkedHashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "sex", length = 30)
    private Sex sex;

    @Column(name = "collected_by_visual_observation_or_surname")
    private Boolean collectedByVisualObservationOrSurname;

    @Enumerated(EnumType.STRING)
    @Column(name = "application_taken_method", length = 30)
    private ApplicationTakenMethod applicationTakenMethod;
}
