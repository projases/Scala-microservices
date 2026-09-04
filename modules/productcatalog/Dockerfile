# Build stage: assemble the stub's fat jar with sbt.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Install the sbt launcher (launches the sbt version pinned in build.properties).
RUN curl -fsSL https://github.com/sbt/sbt/releases/download/v1.10.7/sbt-1.10.7.tgz \
    | tar xz -C /opt \
    && ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt

COPY project/ project/
COPY build.sbt .
COPY modules/ modules/

RUN sbt -batch -no-colors "productcatalog/assembly"

# Run stage: a slim JRE plus the fat jar and the Docker-aware config.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/modules/productcatalog/target/scala-3.9.0/productcatalog-assembly.jar app.jar
COPY docker/productcatalog.conf application.conf
EXPOSE 18081
ENTRYPOINT ["java", "-Dconfig.file=/app/application.conf", "-jar", "/app/app.jar"]
