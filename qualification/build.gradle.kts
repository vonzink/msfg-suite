dependencies {
    implementation(project(":platform"))
    implementation(project(":loan-core"))
    implementation(project(":parties"))
    implementation(project(":income"))
    implementation(project(":financials"))
    implementation(project(":reo"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
