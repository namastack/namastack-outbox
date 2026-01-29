FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

COPY build/libs/namastack-outbox-performance-test-producer-0.1.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
