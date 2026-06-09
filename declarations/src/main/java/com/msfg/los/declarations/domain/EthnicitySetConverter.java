package com.msfg.los.declarations.domain;

import jakarta.persistence.Converter;

@Converter
public class EthnicitySetConverter extends EnumSetConverter<Ethnicity> {
    public EthnicitySetConverter() { super(Ethnicity.class); }
}
