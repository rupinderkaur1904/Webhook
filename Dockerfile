# syntax=docker/dockerfile:1
#
# Two images from one build:
#   docker build -t webhook-platform .                          # the platform (API + worker + dashboard)
#   docker build -t webhook-simulator --target simulator .      # the receiver simulator
#
# Builds run tests in CI, not here (-DskipTests), so an image build needs no database.

# ---- Dashboard: React app -> static files --------------------------------------------------------
FROM node:22-alpine AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- Java modules --------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS backend
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY backend/pom.xml backend/pom.xml
COPY receiver-simulator/pom.xml receiver-simulator/pom.xml
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline -DskipTests || true
COPY backend/src backend/src
COPY receiver-simulator/src receiver-simulator/src
# The dashboard is served by Spring Boot from classpath:/static (same origin as the API).
COPY --from=frontend /src/frontend/dist backend/src/main/resources/static
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
 && mkdir -p /out \
 && cp backend/target/webhook-platform-*.jar /out/app.jar \
 && cp receiver-simulator/target/receiver-simulator-*.jar /out/simulator.jar

# ---- Receiver simulator --------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS simulator
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=backend /out/simulator.jar /app/simulator.jar
USER app
ENV PORT=9090 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 9090
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${PORT}/healthz" || exit 1
ENTRYPOINT ["java", "-jar", "/app/simulator.jar"]

# ---- Platform (default target) -------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS app
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=backend /out/app.jar /app/app.jar
USER app
# Exec form: SIGTERM reaches the JVM directly, so the worker drains in-flight deliveries
# (webhooks.worker.shutdown-timeout) before the process exits.
ENV PORT=8080 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${PORT}/actuator/health" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
