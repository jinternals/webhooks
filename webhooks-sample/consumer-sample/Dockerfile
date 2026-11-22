# syntax=docker/dockerfile:1
#
# Build the standalone consumer sample. The build context is the REPOSITORY ROOT (see
# docker-compose.yml), so this can first install the webhooks-* libraries into the build stage's local
# Maven repo, then build the sample against them.
#
#   docker build -f sample/consumer-sample/Dockerfile -t webhooks-consumer-sample .
#
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# 1) Build + install the libraries into the local repo. The root reactor lists all library modules, so
#    we copy them all; -pl then narrows the install to just the consumer chain the sample needs (the
#    consumer starter brings the autoconfigure module transitively).
COPY pom.xml ./
COPY webhooks-core/ webhooks-core/
COPY webhooks-consumer-autoconfigure/ webhooks-consumer-autoconfigure/
COPY webhooks-consumer-starter/ webhooks-consumer-starter/
COPY webhooks-producer-autoconfigure/ webhooks-producer-autoconfigure/
COPY webhooks-producer-starter/ webhooks-producer-starter/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -pl webhooks-core,webhooks-consumer-autoconfigure,webhooks-consumer-starter -am install -DskipTests

# 2) Build the sample app itself.
COPY sample/pom.xml sample/pom.xml
COPY sample/consumer-sample/ sample/consumer-sample/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -f sample/pom.xml -pl consumer-sample -am package -DskipTests \
    && cp sample/consumer-sample/target/consumer-sample-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
EXPOSE 9091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
