dependencies {
    implementation(project(":platform"))
    implementation(project(":loan-core"))
    implementation(project(":qualification"))
    implementation(project(":documents"))
    implementation(project(":parties"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
