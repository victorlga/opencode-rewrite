#!/usr/bin/env bash
# Setup script for Devin machine or local dev environment.
# Installs Java 21, Clojure CLI, and clj-kondo.
set -euo pipefail

echo "=== opencode-rewrite dev setup ==="

# --- Java 21 ---
if java -version 2>&1 | grep -q 'version "21'; then
  echo "✓ Java 21 already installed"
else
  echo "Installing Java 21 (Temurin)..."
  if command -v apt-get &>/dev/null; then
    sudo apt-get update -qq
    sudo apt-get install -y -qq wget apt-transport-https gpg
    wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
    echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
    sudo apt-get update -qq
    sudo apt-get install -y -qq temurin-21-jdk
  elif command -v brew &>/dev/null; then
    brew install --cask temurin@21
  else
    echo "ERROR: No supported package manager found. Install Java 21 manually."
    exit 1
  fi
fi

# --- Clojure CLI ---
if command -v clj &>/dev/null; then
  echo "✓ Clojure CLI already installed"
else
  echo "Installing Clojure CLI..."
  if command -v brew &>/dev/null; then
    brew install clojure/tools/clojure
  else
    curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
    chmod +x linux-install.sh
    sudo ./linux-install.sh
    rm linux-install.sh
  fi
fi

# --- clj-kondo ---
if command -v clj-kondo &>/dev/null; then
  echo "✓ clj-kondo already installed"
else
  echo "Installing clj-kondo..."
  if command -v brew &>/dev/null; then
    brew install borkdude/brew/clj-kondo
  else
    curl -sLO https://raw.githubusercontent.com/clj-kondo/clj-kondo/master/script/install-clj-kondo
    chmod +x install-clj-kondo
    sudo ./install-clj-kondo
    rm install-clj-kondo
  fi
fi

# --- Resolve dependencies ---
echo "Resolving project dependencies..."
cd "$(dirname "$0")/.."
clj -P
clj -P -M:dev
clj -P -M:test

echo ""
echo "=== Setup complete ==="
echo "Run 'clj -M:dev' to start a REPL"
echo "Run 'clj -M:test' to run tests"
