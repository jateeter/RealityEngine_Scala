# Build context is the project root (context: .)
# ── Stage 1: Build ─────────────────────────────────────────────────────────
FROM sbtscala/scala-sbt:eclipse-temurin-17.0.15_6_1.12.8_2.13.18 AS build

WORKDIR /build

# Cache dependency downloads by copying build files first
COPY project/ project/
COPY build.sbt ./

RUN --mount=type=cache,target=/root/.sbt,sharing=locked \
    --mount=type=cache,target=/root/.ivy2,sharing=locked \
    --mount=type=cache,target=/root/.cache/coursier,sharing=locked \
    sbt update

# Compile and assemble fat JAR
COPY src/ src/
RUN --mount=type=cache,target=/root/.sbt,sharing=locked \
    --mount=type=cache,target=/root/.ivy2,sharing=locked \
    --mount=type=cache,target=/root/.cache/coursier,sharing=locked \
    sbt assembly

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=build /build/target/scala-2.13/reality-engine.jar ./reality-engine.jar

# /app/machines is volume-mounted from RealityEngine_Machines at runtime.
RUN mkdir -p /app/machines

EXPOSE 3000

ENV PORT=3000 \
    QDRANT_URL=http://qdrant:6333 \
    COLLECTION_NAME=reality-vectors \
    VECTOR_DIMENSION=7680 \
    MACHINES_DIR=/app/machines

CMD ["java", "-jar", "/app/reality-engine.jar"]
