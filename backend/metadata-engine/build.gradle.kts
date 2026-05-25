plugins {
    id("osc.java-conventions")
}

dependencies {
    api("io.projectreactor:reactor-core")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("com.fasterxml.jackson.core:jackson-databind")
}
