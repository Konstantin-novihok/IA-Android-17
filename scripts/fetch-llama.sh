#!/usr/bin/env bash
# Скачивает исходники llama.cpp нужной версии в app/src/main/cpp/llama.cpp
set -euo pipefail
cd "$(dirname "$0")/.."
VER="$(tr -d '\r\n' < app/src/main/cpp/LLAMA_CPP_VERSION.txt)"
DEST=app/src/main/cpp/llama.cpp
rm -rf "$DEST"
git clone --depth 1 --branch "$VER" https://github.com/ggml-org/llama.cpp "$DEST"
rm -rf "$DEST/.git"
echo "llama.cpp $VER готов: $DEST"
