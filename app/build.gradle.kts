plugins { alias(libs.plugins.spring.boot) }

configurations.all {
    // Spring Boot 3.3.5 BOM pins Testcontainers to 1.19.8 which rejects Docker API ≥1.44.
    // Force the version declared in our catalog (1.20.3) which supports API 1.44+.
    resolutionStrategy.eachDependency {
        if (requested.group == "org.testcontainers") {
            useVersion("1.20.3")
            because("Spring Boot BOM downgrades TC; 1.20.3 supports Docker API ≥1.44")
        }
    }
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":loan-core"))
    implementation(project(":parties"))
    implementation(project(":tenancy"))
    implementation(project(":income"))
    implementation(project(":financials"))
    implementation(project(":reo"))
    implementation(project(":qualification"))
    implementation(project(":declarations"))
    implementation(project(":fees"))
    implementation(project(":coc"))
    implementation(project(":documents"))
    implementation(project(":pricing"))
    implementation(project(":aus"))
    implementation(project(":contacts"))
    implementation(project(":disclosures"))
    implementation(project(":identity"))
    implementation(project(":conditions"))
    implementation(project(":notes"))
    implementation(project(":dashboard"))
    implementation(project(":origination"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(libs.springdoc.openapi)
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
}

tasks.withType<Test> {
    // Local macOS Docker Desktop quirk: Testcontainers can't reach the daemon via the default
    // /var/run/docker.sock, and Docker Desktop's proxy enforces Docker API >=1.44 (docker-java
    // otherwise sends /v1.32/ and gets a 400). Apply the workaround ONLY for local dev — when
    // DOCKER_HOST is unset AND the per-user Docker Desktop socket exists. On CI/Linux/colima
    // (DOCKER_HOST set or socket absent) this is skipped and Testcontainers' defaults are used,
    // keeping the build portable.
    val userSock = file("${System.getProperty("user.home")}/.docker/run/docker.sock")
    if (System.getenv("DOCKER_HOST") == null && userSock.exists()) {
        systemProperty("tc.host", "unix://${userSock.absolutePath}")
        systemProperty("api.version", "1.44")
    }
}
