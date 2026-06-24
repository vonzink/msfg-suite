dependencies {
    implementation(project(":platform"))
    implementation(project(":loan-core"))
    // :parties for the staff-initiated borrower-verification controller (security spec §6.2): it reaches
    // BorrowerService (isBorrowerInLoan / resolveContact) through the parties SERVICE, not its repo — the
    // ArchUnit ModuleBoundaryTest forbids cross-module REPOSITORY deps only, so a service dep is legal.
    implementation(project(":parties"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // AWS SDK v2 — Cognito write-side adapter (CognitoUserAdminAdapter). BOM pins versions; the
    // adapter is @ConditionalOnProperty(los.identity.user-admin=cognito), dormant locally/in tests.
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.cognito)
    // @Operation annotations — compile-only; springdoc is on the runtime classpath via :app.
    compileOnly(libs.springdoc.openapi)
}
