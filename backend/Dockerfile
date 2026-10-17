FROM gradle:8.14.3-jdk17 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle --no-daemon installDist

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 fsm && useradd --uid 10001 --gid fsm --no-create-home fsm \
    && mkdir -p /var/lib/fsm/photos && chown -R fsm:fsm /var/lib/fsm
WORKDIR /app
COPY --from=build --chown=fsm:fsm /workspace/build/install/fsm-backend/ ./
ENV PORT=8080 PHOTO_STORAGE_DIR=/var/lib/fsm/photos
USER fsm
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 CMD curl -fsS http://localhost:8080/health || exit 1
ENTRYPOINT ["/app/bin/fsm-backend"]
