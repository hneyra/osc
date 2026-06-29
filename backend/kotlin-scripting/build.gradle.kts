plugins {
    id("osc.java-conventions")
    kotlin("jvm") version "2.2.21"
}

dependencies {
    implementation(project(":backend:metadata-engine"))
    implementation(project(":backend:persistence"))
    implementation(project(":backend:query-engine"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("io.projectreactor:reactor-core")

    testImplementation("org.mockito:mockito-core")
    testImplementation("io.projectreactor:reactor-test")
}

// NNG-004 scoped exception (ADR-005): this is the only module allowed to call .block(),
// and only inside classes scheduled on Schedulers.boundedElastic(). Enforced by
// KotlinScriptingBlockingIsolationRule (see issue tracking ADR-005 implementation).
