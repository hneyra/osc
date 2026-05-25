// Spring AI 2.x wired in Phase 6 (issue #7)
// Skeleton: empty Java library that depends only on metadata-engine
plugins {
    id("osc.java-conventions")
}

dependencies {
    implementation(project(":backend:metadata-engine"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
}
