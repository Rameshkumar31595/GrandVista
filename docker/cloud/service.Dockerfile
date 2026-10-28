# syntax=docker/dockerfile:1
#
# CLOUD (Render) image for every Spring Boot service. Same build as docker/service.Dockerfile - which stays the recipe
# for all local docker-compose files - plus a small entrypoint that activates the "cloud" Spring profile, assembles the
# authenticated Eureka URL and sizes the JVM for a 512 MB free instance. Build context = repository root:
#
#   docker build -f docker/cloud/service.Dockerfile --build-arg SERVICE_DIR=room-service -t grandvista-cloud/room-service .
#
# Nothing secret is copied into the image (.dockerignore); all configuration arrives as environment variables.

# ---------- build stage ----------
FROM maven:3-eclipse-temurin-25 AS build
ARG SERVICE_DIR
WORKDIR /build

COPY ${SERVICE_DIR}/pom.xml ./pom.xml
RUN mvn -B -q dependency:go-offline

COPY ${SERVICE_DIR}/src ./src
# Tests run locally / in CI (mvn test), not while building an image.
RUN mvn -B -q package -DskipTests

# ---------- runtime stage ----------
FROM eclipse-temurin:25-jre AS runtime
# curl: health checks of the local cloud simulation; postgresql-client: first-start database bootstrap (entrypoint)
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl postgresql-client \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin grandvista

WORKDIR /app
COPY --from=build /build/target/*.jar /app/app.jar
COPY --chmod=0755 docker/cloud/render-entrypoint.sh /app/render-entrypoint.sh

USER grandvista
# Render injects PORT (default 10000); the cloud profile listens on it.
EXPOSE 10000
ENTRYPOINT ["/app/render-entrypoint.sh"]
