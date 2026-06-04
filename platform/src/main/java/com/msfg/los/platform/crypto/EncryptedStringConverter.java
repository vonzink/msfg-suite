package com.msfg.los.platform.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Converter(autoApply = false)
public class EncryptedStringConverter implements AttributeConverter<String, String> {
    @Autowired private NpiCipher cipher;
    @Override public String convertToDatabaseColumn(String attribute) { return cipher.encrypt(attribute); }
    @Override public String convertToEntityAttribute(String dbData) { return cipher.decrypt(dbData); }
}
