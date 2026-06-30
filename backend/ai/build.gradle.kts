plugins {
    id("osc.java-conventions")
}

dependencies {
    implementation(project(":backend:metadata-engine"))
    implementation(project(":backend:kotlin-scripting"))
    implementation("org.jetbrains.kotlin:kotlin-scripting-common")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    // JSON Schema (Draft-07) validation of the metadata contracts in docs/contracts/
    implementation("com.networknt:json-schema-validator:1.5.4")

    testImplementation("org.mockito:mockito-core:5.15.2")
    testImplementation("io.projectreactor:reactor-test")
}

// Publish the canonical contract schemas (single source of truth in docs/contracts/)
// onto the module classpath under `contracts/` so they can be loaded and enforced from code.
val contractsResourceDir = layout.buildDirectory.dir("generated/contracts-resources")

val copyMetadataContracts by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.dir("docs/contracts")) {
        include("*.json")
    }
    into(contractsResourceDir.map { it.dir("contracts") })
}

sourceSets.named("main") {
    resources.srcDir(contractsResourceDir)
}

tasks.named("processResources") {
    dependsOn(copyMetadataContracts)
}
