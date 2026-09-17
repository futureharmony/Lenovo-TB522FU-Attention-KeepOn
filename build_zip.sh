#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="v1.8.0"
ZIP_NAME="Lenovo-TB522FU-Attention-KeepOn-${VERSION}.zip"
OUT_PATH="${SCRIPT_DIR}/${ZIP_NAME}"

echo "🔨 正在打包 ${ZIP_NAME}..."
cd "${SCRIPT_DIR}/magisk_module"

rm -f "${OUT_PATH}"
zip -r9 "${OUT_PATH}" . -x "*.DS_Store" -x "__MACOSX/*"

echo "✅ 打包完成: ${OUT_PATH}"
ls -lh "${OUT_PATH}"
