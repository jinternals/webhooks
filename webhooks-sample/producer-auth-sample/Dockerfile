# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml lombok.config ./
COPY webhooks-core/ webhooks-core/
COPY webhooks-consumer-autoconfigure/ webhooks-consumer-autoconfigure/
COPY webhooks-consumer-starter/ webhooks-consumer-starter/
COPY webhooks-producer-autoconfigure/ webhooks-producer-autoconfigure/
COPY webhooks-producer-starter/ webhooks-producer-starter/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -pl webhooks-core,webhooks-producer-autoconfigure,webhooks-producer-starter -am install -DskipTests

COPY webhooks-sample/ webhooks-sample/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -f webhooks-sample/pom.xml -pl producer-auth-sample -am package -DskipTests \
    && cp webhooks-sample/producer-auth-sample/target/producer-auth-sample-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER ubuntu
EXPOSE 9094
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
