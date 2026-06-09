package com.msfg.los.declarations.domain;

import jakarta.persistence.Converter;

@Converter
public class RaceSetConverter extends EnumSetConverter<Race> {
    public RaceSetConverter() { super(Race.class); }
}
