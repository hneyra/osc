plugins {
    id("osc.java-conventions")
}

dependencies {
    implementation(project(":backend:metadata-engine"))
    implementation(project(":backend:persistence"))
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.postgresql:r2dbc-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:r2dbc")
    // Integration tests run the real Flyway migrations (which live in the persistence module's
    // resources, on the classpath here) against Testcontainers PostgreSQL.
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
