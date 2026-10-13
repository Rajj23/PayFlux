# Stage 1: Build
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn clean package -DskipTests -Dmaven.wagon.http.retryHandler.count=5 -Dmaven.wagon.http.connectionTimeout=60000 -Dmaven.wagon.http.readTimeout=60000

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S payflux && adduser -S payflux -G payflux
COPY --from=builder /app/target/*.jar app.jar
RUN chown -R payflux:payflux /app
USER payflux
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
