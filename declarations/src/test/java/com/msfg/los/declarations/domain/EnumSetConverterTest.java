package com.msfg.los.declarations.domain;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class EnumSetConverterTest {
    static class TestConv extends EnumSetConverter<BankruptcyType> { TestConv(){ super(BankruptcyType.class);} }
    final TestConv c = new TestConv();

    @Test void roundTrip() {
        Set<BankruptcyType> in = new LinkedHashSet<>(List.of(BankruptcyType.CHAPTER_7, BankruptcyType.CHAPTER_13));
        String db = c.convertToDatabaseColumn(in);
        assertThat(db).isEqualTo("CHAPTER_7,CHAPTER_13");
        assertThat(c.convertToEntityAttribute(db)).containsExactly(BankruptcyType.CHAPTER_7, BankruptcyType.CHAPTER_13);
    }

    @Test void nullAndEmpty() {
        assertThat(c.convertToDatabaseColumn(null)).isNull();
        assertThat(c.convertToDatabaseColumn(Set.of())).isNull();
        assertThat(c.convertToEntityAttribute(null)).isEmpty();
        assertThat(c.convertToEntityAttribute("")).isEmpty();
    }
}
