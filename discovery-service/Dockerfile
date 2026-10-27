FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
RUN mvn -B -ntp package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/discovery-service-0.0.1-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
