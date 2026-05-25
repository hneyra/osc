# syntax=docker/dockerfile:1
#
# Multi-stage build for the OSC backend.
# Build context must be the repository root (not the backend/ subdirectory).
# Example: docker build -f backend/Dockerfile .
# In docker-compose, set context: . and dockerfile: backend/Dockerfile

# ---- Build stage ----
FROM eclipse-temurin:25-jdk-alpine AS build

WORKDIR /workspace

# Copy Gradle wrapper and build configuration first for layer caching
COPY gradlew ./
COPY gradle/ gradle/
COPY buildSrc/ buildSrc/
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

# Copy all backend module sources
COPY backend/ backend/

# Build the executable jar (tests run separately in CI)
RUN ./gradlew :backend:api:bootJar -x test --no-daemon

# ---- Runtime stage ----
FROM eclipse-temurin:25-jre-alpine AS runtime

# Create a non-root user for security
RUN addgroup -S osc && adduser -S osc -G osc

WORKDIR /app

# Copy the built jar from the build stage
COPY --from=build /workspace/backend/api/build/libs/*.jar app.jar

# Switch to non-root user
USER osc

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
