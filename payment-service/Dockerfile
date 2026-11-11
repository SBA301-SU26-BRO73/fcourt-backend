FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
RUN mvn -B -ntp package -DskipTests
RUN printf '%s\n' 'import java.net.HttpURLConnection;' 'import java.net.URL;' 'public class Healthcheck {' '  public static void main(String[] args) throws Exception {' '    HttpURLConnection connection = (HttpURLConnection) new URL(args[0]).openConnection();' '    connection.setConnectTimeout(2000);' '    connection.setReadTimeout(2000);' '    connection.setRequestMethod("GET");' '    System.exit(connection.getResponseCode() == 200 ? 0 : 1);' '  }' '}' > Healthcheck.java && javac Healthcheck.java
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/payment-service-0.0.1-SNAPSHOT.jar app.jar
COPY --from=build /app/Healthcheck.class Healthcheck.class
USER 10001
EXPOSE 8089
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 CMD ["java", "-cp", "/app", "Healthcheck", "http://127.0.0.1:8089/actuator/health"]
ENTRYPOINT ["java", "-jar", "app.jar"]
