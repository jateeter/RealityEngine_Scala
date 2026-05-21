# Build context is the project root (context: .)
# ── Stage 1: Build ─────────────────────────────────────────────────────────
FROM sbtscala/scala-sbt:eclipse-temurin-17.0.15_6_1.12.8_2.13.18 AS build

WORKDIR /build

# Cache dependency downloads by copying build files first
COPY scala/project/ project/
COPY scala/build.sbt ./

RUN --mount=type=cache,target=/root/.sbt,sharing=locked \
    --mount=type=cache,target=/root/.ivy2,sharing=locked \
    --mount=type=cache,target=/root/.cache/coursier,sharing=locked \
    sbt update

# Compile and assemble fat JAR
COPY scala/src/ src/
RUN --mount=type=cache,target=/root/.sbt,sharing=locked \
    --mount=type=cache,target=/root/.ivy2,sharing=locked \
    --mount=type=cache,target=/root/.cache/coursier,sharing=locked \
    sbt assembly

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=build /build/target/scala-2.13/reality-engine.jar ./reality-engine.jar

# Machine JSON files loaded at startup by MachineLoader
COPY examples ./examples

EXPOSE 3000

ENV PORT=3000 \
    QDRANT_URL=http://qdrant:6333 \
    COLLECTION_NAME=reality-vectors \
    VECTOR_DIMENSION=256 \
    MACHINES_DIR=/app/examples/machines

CMD ["java", "-jar", "/app/reality-engine.jar"]
