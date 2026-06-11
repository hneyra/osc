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
    // Boot 4 moved Flyway auto-configuration out of spring-boot-autoconfigure; without this
    // starter the app boots without running the db/migration scripts.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    // PostgreSQL dialect support + JDBC driver for the Flyway datasource come in
    // transitively from :backend:persistence (flyway-database-postgresql, postgresql).
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.8.9")

    // AWS SDK — Secrets Manager integration (#60)
    implementation("software.amazon.awssdk:secretsmanager:2.26.0")
    implementation("software.amazon.awssdk:sts:2.26.0")

    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    // For seeding permission fixtures via DatabaseClient in the controller integration test.
    testImplementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    // Integration tests run the real Flyway migrations (packaged in the persistence module)
    // against Testcontainers before the app handles requests.
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
