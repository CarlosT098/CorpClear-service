FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY corpclear-service-api/pom.xml ./corpclear-service-api/pom.xml
COPY corpclear-service-api/src/main ./corpclear-service-api/src/main
RUN mvn --batch-mode -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/corpclear-service-api/target/corpclear-service-api.jar ./app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
