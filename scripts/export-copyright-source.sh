#!/usr/bin/env bash
# 软著源程序鉴别材料导出脚本（RSXM AI 渗透测试工具软件 V2.8.0）
# 产出：前 30 页 + 后 30 页，每页 50 行，页眉「软件名称 + 版本号」，页脚页码。
# 用法：bash scripts/export-copyright-source.sh
set -euo pipefail

APP_NAME="RSXM AI 渗透测试工具软件"
VER="V2.8.0"
SRC="android-app/app/src/main/java/com/rsxm/app/MainActivity.java"
OUT="dist/软著-源程序"
PAGES=30
LINES_PER_PAGE=50

[ -f "$SRC" ] || { echo "找不到 $SRC"; exit 1; }
mkdir -p dist
total=$(wc -l < "$SRC")
head_lines=$((PAGES * LINES_PER_PAGE))
tail_start=$((total - head_lines + 1))
[ $tail_start -lt 1 ] && tail_start=1
echo "源程序 $total 行；前段 1-${head_lines}，后段 ${tail_start}-${total}"

gen() {
  local label="$1" file="$2"
  local lineno=0 page=1
  : > "$file"
  while IFS= read -r line || [ -n "$line" ]; do
    if [ $((lineno % LINES_PER_PAGE)) -eq 0 ]; then
      [ $lineno -gt 0 ] && { printf '\f' >> "$file"; page=$((page + 1)); }
      printf '%-60s%8s\n' "$APP_NAME $VER" "第 ${page} 页" >> "$file"
      printf '%s\n' "────────────────────────────────────────────────────────────────" >> "$file"
    fi
    printf '%s\n' "$line" >> "$file"
    lineno=$((lineno + 1))
  done
  # 对齐页尾（最后一页除外）：不足 50 行补空行
  local rem=$((lineno % LINES_PER_PAGE))
  if [ $rem -ne 0 ]; then
    while [ $rem -lt $LINES_PER_PAGE ]; do printf '\n' >> "$file"; rem=$((rem + 1)); done
  fi
  echo "已生成：$file（$page 页）"
}

sed -n "1,${head_lines}p" "$SRC" | gen "前30页" "$OUT-前30页.txt"
sed -n "${tail_start},\$p" "$SRC" | gen "后30页" "$OUT-后30页.txt"

echo "完成：$OUT-前30页.txt / $OUT-后30页.txt"
echo "提示：将两个 txt 转 PDF（LibreOffice 等），页眉/页码已排版；"
echo "      文档鉴别材料可参考 docs/用户手册-RSXM.md 整理后同样转 PDF。"
