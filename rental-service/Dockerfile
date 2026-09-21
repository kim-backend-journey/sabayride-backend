# SabayRide API — multi-stage build
# Result: ~220 MB image instead of ~800 MB, and no build tools shipped to prod.

# ─── Stage 1: build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copy the dependency descriptor FIRST and resolve dependencies on its own layer.
# Docker caches this layer, so editing a .java file does not re-download the
# internet. Skipping this is why some teams wait 4 minutes for every build.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ─── Stage 2: run ────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Never run as root. A container escape from a root process is a host
# compromise; from an unprivileged user it is far less useful to an attacker.
RUN addgroup -S app && adduser -S -G app app

COPY --from=build /build/target/*.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080

# MaxRAMPercentage matters in small containers. The JVM's default heap sizing
# assumes it owns the machine; on a 1 GB instance it will size a heap that gets
# the process OOM-killed by the kernel with no useful error. 75% leaves room for
# metaspace, thread stacks and the OS.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

# Spring Boot Actuator health endpoint. AWS and docker compose both use this to
# know whether the app is actually up rather than merely started.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

# ─── Gradle instead of Maven? Replace stage 1 with: ──────────────────────────
# FROM gradle:8-jdk21 AS build
# WORKDIR /build
# COPY build.gradle settings.gradle ./
# RUN gradle dependencies --no-daemon || true
# COPY src ./src
# RUN gradle bootJar --no-daemon -x test
# ... and copy from /build/build/libs/*.jar
