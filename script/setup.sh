#!/usr/bin/env bash
set -euo pipefail

# opencode-rewrite development environment setup
# Installs Java 21, Clojure CLI, and clj-kondo

echo "=== opencode-rewrite setup ==="

# --- Java 21 ---
if java -version 2>&1 | grep -q '"21\.'; then
  echo "[OK] Java 21 already installed"
else
  echo "[INSTALL] Java 21..."
  sudo apt-get update -qq
  sudo apt-get install -y -qq openjdk-21-jdk-headless
  sudo update-java-alternatives -s java-1.21.0-openjdk-amd64 2>/dev/null || true
  echo "[OK] Java 21 installed"
fi

# --- Clojure CLI ---
if command -v clj &>/dev/null; then
  echo "[OK] Clojure CLI already installed: $(clj --version 2>&1 | head -1)"
else
  echo "[INSTALL] Clojure CLI..."
  curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
  chmod +x linux-install.sh
  sudo bash linux-install.sh
  rm linux-install.sh
  echo "[OK] Clojure CLI installed"
fi

# --- rlwrap (for clj REPL) ---
if command -v rlwrap &>/dev/null; then
  echo "[OK] rlwrap already installed"
else
  echo "[INSTALL] rlwrap..."
  sudo apt-get install -y -qq rlwrap
  echo "[OK] rlwrap installed"
fi

# --- clj-kondo ---
if command -v clj-kondo &>/dev/null; then
  echo "[OK] clj-kondo already installed: $(clj-kondo --version)"
else
  echo "[INSTALL] clj-kondo..."
  curl -sLO https://raw.githubusercontent.com/clj-kondo/clj-kondo/master/script/install-clj-kondo
  chmod +x install-clj-kondo
  sudo ./install-clj-kondo
  rm install-clj-kondo
  echo "[OK] clj-kondo installed"
fi

# --- Resolve dependencies ---
echo "[DEPS] Resolving project dependencies..."
cd "$(dirname "$0")/.."
clj -P
clj -P -M:dev
clj -P -M:test

echo ""
echo "=== Setup complete ==="
echo "Verify with:"
echo '  clj -M -e '"'"'(println "ready")'"'"''
echo "  clj-kondo --version"
