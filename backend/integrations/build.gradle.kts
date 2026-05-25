plugins {
    id("osc.java-conventions")
}

dependencies {
    implementation(project(":backend:metadata-engine"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    testImplementation("org.mockito:mockito-core:5.15.2")
    testImplementation("io.projectreactor:reactor-test")
}
