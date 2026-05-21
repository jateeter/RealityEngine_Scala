# Build context is the project root (context: .)
# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM sbtscala/scala-sbt:eclipse-temurin-17.0.15_6_1.12.8_2.13.18 AS build

WORKDIR /build

# Cache dependency downloads by copying build files first
COPY scala/perception-engine/project/ project/
COPY scala/perception-engine/build.sbt build.sbt

RUN --mount=type=cache,target=/root/.sbt,sharing=locked \
    --mount=type=cache,target=/root/.ivy2,sharing=locked \
    --mount=type=cache,target=/root/.cache/coursier,sharing=locked \
    sbt update

# Compile and assemble fat JAR
COPY scala/perception-engine/src/ src/
RUN --mount=type=cache,target=/root/.sbt,sharing=locked \
    --mount=type=cache,target=/root/.ivy2,sharing=locked \
    --mount=type=cache,target=/root/.cache/coursier,sharing=locked \
    sbt assembly

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=build /build/target/scala-2.13/perception-engine.jar ./perception-engine.jar

EXPOSE 3004

ENV PORT=3004 \
    PERCEPTION_TARGET_URL=http://visualizer-backend:3001 \
    REALITY_ENGINE_URL=http://reality-engine:3000 \
    DATA_PATH=/app/data

CMD ["java", "-jar", "/app/perception-engine.jar"]
