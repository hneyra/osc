plugins {
    `java-library`
    id("io.spring.dependency-management")
}

group = "dev.osc"
version = "0.1.0-SNAPSHOT"

// Default to Java 21 locally; override with -PjavaVersion=25 in CI environments with Java 25 installed
val targetJava: Int = providers.gradleProperty("javaVersion").map { it.toInt() }.getOrElse(21)

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(targetJava))
    }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.0")
        mavenBom("org.testcontainers:testcontainers-bom:1.20.4")
    }
}

dependencies {
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}
