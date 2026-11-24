# syntax=docker/dockerfile:1
#
# Build the standalone producer sample. The build context is the REPOSITORY ROOT (see
# docker-compose.yml), so this can first install the webhooks-* libraries into the build stage's local
# Maven repo, then build the sample against them.
#
#   docker build -f webhooks-sample/producer-sample/Dockerfile -t webhooks-producer-sample .
#
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# 1) Build + install the libraries into the local repo. The root reactor lists all library modules, so
#    we copy them all; -pl then narrows the install to just the producer chain the sample needs (the
#    producer starter brings the autoconfigure module transitively).
COPY pom.xml lombok.config ./
COPY webhooks-core/ webhooks-core/
COPY webhooks-consumer-autoconfigure/ webhooks-consumer-autoconfigure/
COPY webhooks-consumer-starter/ webhooks-consumer-starter/
COPY webhooks-producer-autoconfigure/ webhooks-producer-autoconfigure/
COPY webhooks-producer-starter/ webhooks-producer-starter/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -pl webhooks-core,webhooks-producer-autoconfigure,webhooks-producer-starter -am install -DskipTests

# 2) Build the sample app itself.
COPY webhooks-sample/ webhooks-sample/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -f webhooks-sample/pom.xml -pl producer-sample -am package -DskipTests \
    && cp webhooks-sample/producer-sample/target/producer-sample-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER ubuntu
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
