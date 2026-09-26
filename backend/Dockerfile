# Phase 16 production image.
# Multi-stage: Maven builds the jar, a JRE runtime carries it. No build tooling in the final image.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
# Prefetch dependencies through a copied script: see the comment inside docker-prefetch.sh.
COPY pom.xml docker-prefetch.sh ./
RUN sh docker-prefetch.sh && rm docker-prefetch.sh
COPY src src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

# Non-root runtime user; owns only the private storage volume.
RUN useradd --system --uid 10001 --create-home app \
    && mkdir -p /data/private \
    && chown -R app:app /data/private /app
USER app

COPY --from=build --chown=app:app /workspace/target/backend-1.0.0.jar app.jar

EXPOSE 8080

# curl rather than /dev/tcp: that construct is a bash extension and /bin/sh is dash on this base
# image, so the check would fail even when the application is perfectly healthy.
# The probe is unauthenticated and never touches dependencies, so a database blip does not
# mark the container unhealthy and cause a restart loop.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD ["curl", "-fsS", "-o", "/dev/null", "http://127.0.0.1:8080/actuator/health/liveness"]

# Graceful shutdown lets in-flight requests finish and the JVM exit cleanly on SIGTERM.
# Heap sizing is a starting point and is overridable per deployment without rebuilding the image.
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-XX:+UseContainerSupport", \
    "-Dserver.shutdown=graceful", \
    "-jar", "/app/app.jar"]
