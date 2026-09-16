# syntax=docker/dockerfile:1
# Build stage: assemble the service's fat jar with sbt.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Install the sbt launcher (launches the sbt version pinned in build.properties).
RUN curl -fsSL https://github.com/sbt/sbt/releases/download/v1.12.11/sbt-1.12.11.tgz \
    | tar xz -C /opt \
    && ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt

COPY project/ project/
COPY build.sbt .
COPY modules/ modules/

# The coursier and sbt mount caches survive rebuilds and are shared across all images.
RUN --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/root/.sbt \
    sbt -batch -no-colors "microcredential/assembly"

# Run stage: a slim JRE plus the fat jar and the Docker-aware config.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/modules/microcredential/target/scala-3.9.0/microcredential-assembly.jar app.jar
COPY docker/microcredential.conf application.conf
EXPOSE 18085
ENTRYPOINT ["java", "-Dconfig.file=/app/application.conf", "-jar", "/app/app.jar"]
