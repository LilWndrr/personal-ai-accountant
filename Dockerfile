# --- Build stage: compile and package the Spring Boot jar ---
FROM maven:3.9.16-eclipse-temurin-26 AS build
WORKDIR /build

# Resolve dependencies in their own layer so source-only changes reuse the cache.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests need a live Postgres/Redis; they belong in CI, not the image build.
RUN mvn -B -q package -DskipTests \
    && cp target/*.jar app.jar

# --- Runtime stage: slim JRE only ---
# Ubuntu-based (not Alpine) on purpose: PDFBox renders PDF pages to images via
# AWT, which needs the fontconfig/fonts the Temurin Ubuntu images ship with.
FROM eclipse-temurin:26-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app \
    && mkdir -p /app/logs && chown -R app:app /app

COPY --from=build --chown=app:app /build/app.jar app.jar

USER app

# The rotating log file (logging.file.name=logs/...) resolves relative to /app.
VOLUME /app/logs

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.awt.headless=true", "-jar", "app.jar"]
