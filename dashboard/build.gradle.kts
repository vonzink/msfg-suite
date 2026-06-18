dependencies {
    implementation(project(":platform"))
    implementation(project(":loan-core"))
    implementation(project(":parties"))
    implementation(project(":fees"))
    implementation(project(":contacts"))
    implementation(project(":conditions"))
    implementation(project(":notes"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
