# ---- Build stage: compile the Ktor server into build/install/VibesQuantumServer ----
# Uses the official Gradle image because the upload has no gradle-wrapper.jar.
FROM gradle:9.6.1-jdk21 AS build
WORKDIR /src

# Copy build config first so dependency downloads are cached between deploys
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN gradle dependencies --no-daemon > /dev/null 2>&1 || true

COPY src ./src
RUN gradle installDist --no-daemon -x test

# ---- Runtime stage: JRE only, non-root user ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --uid 10001 app \
    && mkdir -p /app/data \
    && chown -R app:app /app

COPY --from=build /src/build/install/VibesQuantumServer/ /app/

USER app

# Render sets PORT automatically; the app reads it (defaults to 8080)
ENV PORT=8080 \
    HOST=0.0.0.0
EXPOSE 8080

CMD ["bin/VibesQuantumServer"]
