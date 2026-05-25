plugins {
    id("osc.java-conventions")
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":backend:metadata-engine"))
    implementation(project(":backend:persistence"))
    implementation(project(":backend:query-engine"))
    implementation(project(":backend:security"))
    implementation(project(":backend:automation"))
    implementation(project(":backend:ai"))
    implementation(project(":backend:integrations"))

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}
