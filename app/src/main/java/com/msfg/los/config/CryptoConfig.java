package com.msfg.los.config;

import com.msfg.los.platform.crypto.NpiCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CryptoConfig {
    @Bean
    NpiCipher npiCipher(@Value("${los.npi.key}") String key) { return new NpiCipher(key); }
}
