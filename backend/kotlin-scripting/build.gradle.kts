plugins {
    id("osc.java-conventions")
    kotlin("jvm") version "2.2.21"
}

dependencies {
    implementation(project(":backend:metadata-engine"))
    implementation(project(":backend:persistence"))
    implementation(project(":backend:query-engine"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlin:kotlin-scripting-common")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("io.projectreactor:reactor-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.mockito:mockito-core")
    testImplementation("io.projectreactor:reactor-test")
}

// NNG-004 scoped exception (ADR-005): this is the only module allowed to call .block(),
// and only inside classes scheduled on Schedulers.boundedElastic(). Enforced by
// KotlinScriptingBlockingIsolationRule (see issue tracking ADR-005 implementation).
