# ==========================================
# Stage 1: Build the Application
# ==========================================
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /build

# Copy Maven descriptor and resolve dependencies (enables layer caching)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build jar
COPY src ./src
RUN mvn clean package -DskipTests -B

# ==========================================
# Stage 2: Testing
# ==========================================
FROM builder AS tester
RUN mvn test -B

# ==========================================
# Stage 3: Production Runtime
# ==========================================
FROM eclipse-temurin:17-jre-alpine AS runner
WORKDIR /app

# Install security patches and create a non-root system group & user
RUN apk update && apk upgrade && \
    addgroup -g 10001 -S spring && \
    adduser -u 10001 -S spring -G spring

# Ensure test stage runs successfully before creating runner image
COPY --from=tester /build/pom.xml /tmp/dummy_test_check

# Copy the built jar file from the builder stage
COPY --from=builder /build/target/cloudshare-*.jar ./app.jar

# Adjust ownership to the non-root user
RUN chown -R spring:spring /app
USER spring:spring

# Expose backend API port
EXPOSE 8080

# Container-level health check. Hits the Actuator liveness probe (exposed via
# management.endpoint.health.probes.enabled=true) and is permitted without
# authentication in SecurityConfig (/actuator/health/**). Uses wget since the
# eclipse-temurin:17-jre-alpine base includes it via busybox; no curl is present.
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

# Environment-agnostic JVM tuning parameters
ENV JAVA_OPTS="-XX:+UseG1GC \
    -XX:MaxRAMPercentage=75.0 \
    -XX:MinRAMPercentage=50.0 \
    -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
