FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
RUN mvn -B -ntp package -DskipTests
RUN find target -maxdepth 1 -name '*.jar' ! -name '*sources*' ! -name '*tests*' -exec cp {} /app/service.jar \;
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/service.jar app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "app.jar"]
