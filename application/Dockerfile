FROM maven:3.9.8-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B clean package -DskipTests
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/target/document-migration-worker.jar /app/app.jar
USER 10001
ENTRYPOINT ["java","-jar","/app/app.jar"]
