# ---- Stage 1: build the JAR ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q clean package -DskipTests

# ---- Stage 2: run (Java + Chromium for Playwright) ----
FROM mcr.microsoft.com/playwright/java:v1.48.0-jammy
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
