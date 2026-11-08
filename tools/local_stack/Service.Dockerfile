# syntax=docker/dockerfile:1.7
# Compose uses one service directory as the build context and passes SERVICE.
FROM maven:3.9.11-eclipse-temurin-21 AS build
# Cap the Maven JVM so a build does not balloon the WSL VM on a low-RAM host.
ENV MAVEN_OPTS="-Xmx512m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
ARG SERVICE
WORKDIR /workspace/service
COPY . .
# Use the Maven that ships in the build image. The checked-out mvnw can have CRLF line endings on Windows,
# which makes its shebang fail inside Linux ("./mvnw: not found"), and it would download Maven again anyway.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -f pom.xml -DskipTests package \
 && find target -maxdepth 1 -type f -name '*.jar' ! -name '*sources*' ! -name '*tests*' -exec cp {} /workspace/service.jar \;

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --home-dir /app app
COPY --from=build --chown=10001:10001 /workspace/service.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-XX:+UseSerialGC", "-Xss512k", "-XX:TieredStopAtLevel=1", "-jar", "/app/app.jar"]
