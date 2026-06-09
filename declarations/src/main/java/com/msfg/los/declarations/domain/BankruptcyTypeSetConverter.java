package com.msfg.los.declarations.domain;

import jakarta.persistence.Converter;

@Converter
public class BankruptcyTypeSetConverter extends EnumSetConverter<BankruptcyType> {
    public BankruptcyTypeSetConverter() { super(BankruptcyType.class); }
}
