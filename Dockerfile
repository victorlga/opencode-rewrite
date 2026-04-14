# ---------------------------------------------------------------------------
# Dockerfile for opencode-rewrite
# Multi-stage build with dependency caching for fast rebuilds.
# ---------------------------------------------------------------------------

# -- Base: Java 21 + Clojure CLI + ripgrep --------------------------------
FROM eclipse-temurin:21-jdk-jammy AS base

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl rlwrap ripgrep && \
    curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh && \
    chmod +x linux-install.sh && \
    ./linux-install.sh && \
    rm linux-install.sh && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# -- Deps: cached layer (only re-runs when deps.edn changes) --------------
FROM base AS deps

WORKDIR /app
COPY deps.edn /app/
RUN clj -P && clj -P -M:run

# -- App: copy source on top of cached deps -------------------------------
FROM deps AS app

COPY . /app/

ENTRYPOINT ["clj", "-M:run"]
