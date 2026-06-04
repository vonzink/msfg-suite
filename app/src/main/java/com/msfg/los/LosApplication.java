package com.msfg.los;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.msfg.los")
@EntityScan("com.msfg.los")
@EnableJpaRepositories("com.msfg.los")
public class LosApplication {
    public static void main(String[] args) { SpringApplication.run(LosApplication.class, args); }
}
