plugins {
    `kotlin-dsl`
}

repositories {
    maven { url = uri("https://repo.spring.io/milestone") }
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.0.0")
    implementation("io.spring.gradle:dependency-management-plugin:1.1.7")
}
