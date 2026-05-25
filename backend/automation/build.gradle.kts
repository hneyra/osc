plugins {
    id("osc.java-conventions")
}

dependencies {
    implementation(project(":backend:metadata-engine"))
    implementation(project(":backend:persistence"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.postgresql:r2dbc-postgresql")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.mockito:mockito-core")
    testImplementation("io.projectreactor:reactor-test")
}
