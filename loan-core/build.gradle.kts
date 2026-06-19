dependencies {
    implementation(project(":platform"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // @Operation annotations — compile-only; springdoc is on the runtime classpath via :app.
    compileOnly(libs.springdoc.openapi)
}
