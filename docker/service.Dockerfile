# syntax=docker/dockerfile:1
#
# One image recipe for every Spring Boot service (eureka-server, api-gateway, auth-service, room-service,
# booking-service, payment-service). Build context = repository root; select the service with SERVICE_DIR:
#
#   docker build -f docker/service.Dockerfile --build-arg SERVICE_DIR=room-service -t grandvista/room-service .
#
# Nothing secret is copied into the image (.dockerignore); all configuration arrives as environment variables.

# ---------- build stage ----------
FROM maven:3-eclipse-temurin-25 AS build
ARG SERVICE_DIR
WORKDIR /build

# dependencies first, so they stay cached until the pom changes
COPY ${SERVICE_DIR}/pom.xml ./pom.xml
RUN mvn -B -q dependency:go-offline

COPY ${SERVICE_DIR}/src ./src
# Tests run in CI / locally (mvn test), not while building an image.
RUN mvn -B -q package -DskipTests

# ---------- runtime stage ----------
FROM eclipse-temurin:25-jre AS runtime
# curl is used by the compose health checks
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin grandvista

WORKDIR /app
COPY --from=build /build/target/*.jar /app/app.jar

USER grandvista
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
