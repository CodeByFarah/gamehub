# ---------------------------------------------------------------------------
# GameHub backend image.
#
# Multi-stage. The build stage carries the JDK, Gradle and the whole
# dependency cache; the runtime stage carries a JRE and one jar. Shipping the
# build stage would mean shipping a compiler and the full source tree to
# production, which is both larger and more attack surface than necessary.
# ---------------------------------------------------------------------------

# --- Stage 1: build --------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# Wrapper and build scripts first, dependencies resolved before any source is
# copied. Source changes on every commit; dependencies change rarely, so this
# ordering means an ordinary code change reuses the cached dependency layer
# instead of re-downloading the world.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./

RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon --quiet || true

COPY src ./src

# Tests are not run here. They need Postgres, Redis and Kafka, which a docker
# build has no access to; CI runs them before the image is ever built. A build
# that silently skipped its own tests would be worse than one that never
# claimed to run them.
RUN ./gradlew bootJar --no-daemon -x test

# --- Stage 2: runtime ------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# wget for the container healthcheck, which is the only reason it is here.
RUN apk add --no-cache wget

# Non-root. A container process that does not need to write outside its own
# working directory should not be able to.
RUN addgroup -S gamehub && adduser -S gamehub -G gamehub

WORKDIR /app
COPY --from=build --chown=gamehub:gamehub /build/build/libs/gamehub-backend.jar app.jar

USER gamehub

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the JVM then sizes the heap from
# the container memory limit, so the same image behaves correctly whether it
# is given 512MB locally or 2GB on Cloud Run. A hard -Xmx would either waste
# the larger allocation or get the process OOM-killed in the smaller one.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# ExitOnOutOfMemoryError is deliberate. A JVM that has exhausted its heap is
# not going to recover, and a process that keeps answering health checks while
# failing every request is worse than one that dies and gets replaced.

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q UP || exit 1

# exec form, so the JVM is PID 1 and receives SIGTERM directly. Without it a
# shell would be PID 1, the JVM would never see the signal, and every deploy
# would end in a 30-second kill instead of a graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
