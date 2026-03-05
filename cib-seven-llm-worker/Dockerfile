# Multi-stage build for CIB Seven LLM Worker

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the built JAR from the build stage
COPY --from=build /app/target/cib-seven-llm-worker-0.0.1-SNAPSHOT.jar app.jar

# Expose the application port
EXPOSE 8082

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]

