#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 版本号单一来源 = magisk_module/module.prop。
# 与 .github/workflows/release.yml 的 "tag 必须等于 module.prop version" 校验同源，
# 端到端只有一个数字。此前这里硬编码 VERSION，与 module.prop 双份维护 ——
# 改一处忘另一处就会产出"文件名版本 ≠ 模块内版本"的 zip。
VERSION="$(grep '^version=' "${SCRIPT_DIR}/magisk_module/module.prop" | head -1 | cut -d= -f2 | tr -d '[:space:]')"
if [ -z "$VERSION" ]; then
    echo "❌ 无法从 magisk_module/module.prop 读取 version=" >&2
    exit 1
fi

ZIP_NAME="Lenovo-TB522FU-Attention-KeepOn-${VERSION}.zip"
OUT_PATH="${SCRIPT_DIR}/${ZIP_NAME}"

echo "🔨 正在打包 ${ZIP_NAME}（module.prop version=${VERSION}）..."
cd "${SCRIPT_DIR}/magisk_module"

rm -f "${OUT_PATH}"
zip -r9 "${OUT_PATH}" . -x "*.DS_Store" -x "__MACOSX/*"

echo "✅ 打包完成: ${OUT_PATH}"
ls -lh "${OUT_PATH}"
