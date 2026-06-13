FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY gradle gradle
COPY gradlew .
COPY build.gradle .
COPY settings.gradle .
RUN chmod +x gradlew

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine AS runner
RUN addgroup -S cadence && adduser -S cadence -G cadence

WORKDIR /app
COPY --from=builder /workspace/build/libs/cadence-backend.jar cadence-backend.jar

RUN chown cadence:cadence /app/cadence-backend.jar
USER cadence

EXPOSE 8080 8081

HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
    CMD wget -qO- http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/cadence-backend.jar"]
