package com.msfg.los.platform.config;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement(order = 0)   // tx advisor = order 0 (outermost); RLS aspect = @Order(100) (inner)
public class TenantTransactionConfig {}
