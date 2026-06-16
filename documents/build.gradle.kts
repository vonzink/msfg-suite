dependencies {
    implementation(project(":platform"))
    implementation(project(":loan-core"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // AWS SDK v2 — S3 prod adapter (S3Client + S3Presigner ship in the s3 artifact).
    // BOM pins all aws transitive versions; version is in the libs catalog (awsSdk).
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
