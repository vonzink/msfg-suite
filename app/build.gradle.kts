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
}

tasks.withType<Test> {
    // Docker Desktop on macOS: its security proxy enforces Docker API ≥1.44 for
    // certain clients. docker-java defaults to sending /v1.32/ URLs.
    // - tc.host: forces TestcontainersHostPropertyClientProviderStrategy (bypasses
    //   broken UnixSocket/DockerDesktop strategies that hit the CLI proxy socket)
    // - api.version: docker-java reads this from System.getProperties() via
    //   DefaultDockerClientConfig.createDefaultConfigBuilder() → fixes the 400
    val dockerSock = "unix:///Users/${System.getProperty("user.name")}/.docker/run/docker.sock"
    systemProperty("tc.host", dockerSock)
    systemProperty("api.version", "1.44")
}
