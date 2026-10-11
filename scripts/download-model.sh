#!/bin/bash
# Скачивает модель MoveNet MultiPose Lightning (float16, TFLite) с Kaggle и кладёт
# её в assets приложения. Модель в репозиторий не входит.
#
# Использование: scripts/download-model.sh [адрес_прокси]
#   адрес_прокси — например http://127.0.0.1:8888 (по умолчанию берётся из
#   переменной окружения https_proxy, если она задана; иначе прямое соединение).
set -euo pipefail

URL="https://www.kaggle.com/api/v1/models/google/movenet/tfLite/multipose-lightning-tflite-float16/1/download"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/movenet_multipose_lightning.tflite"
TMP="$ROOT/dl"

PROXY="${1:-${https_proxy:-}}"
CURL=(curl -fsSL --max-time 600)
[ -n "$PROXY" ] && CURL+=(-x "$PROXY")

if [ -s "$DEST" ]; then
    echo "Модель уже есть: $DEST"
    exit 0
fi

mkdir -p "$TMP" "$(dirname "$DEST")"
# Kaggle отвечает редиректом на подписанную ссылку storage.googleapis.com;
# внутри архива лежит единственный файл 1.tflite.
"${CURL[@]}" -o "$TMP/movenet.tar.gz" "$URL"
tar -xzf "$TMP/movenet.tar.gz" -C "$TMP"
head -c 8 "$TMP/1.tflite" | grep -q TFL3 || { echo "Скачан не TFLite-файл" >&2; exit 1; }
mv "$TMP/1.tflite" "$DEST"
rm -rf "$TMP"
echo "Готово: $DEST"
