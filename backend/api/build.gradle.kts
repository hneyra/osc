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
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.8.9")

    // AWS SDK — Secrets Manager integration (#60)
    implementation("software.amazon.awssdk:secretsmanager:2.26.0")
    implementation("software.amazon.awssdk:sts:2.26.0")

    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
}
