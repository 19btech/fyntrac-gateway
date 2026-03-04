# ── Stage 1: Build ────────────────────────────────────────────────────
FROM gradle:8.12-jdk21-alpine AS build
WORKDIR /app
COPY . .
RUN gradle bootJar --no-daemon -x test --no-build-cache

# ── Stage 2: Runtime ──────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build /app/build/libs/*.jar app.jar

RUN chown -R appuser:appgroup /app
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
