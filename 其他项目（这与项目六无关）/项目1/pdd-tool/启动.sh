#!/bin/sh
cd "$(dirname "$0")" || exit 1
echo ""
echo "  拼多多规格生成器 启动中..."
echo "  ------------------------------------"
if [ ! -d node_modules ]; then
  echo "  首次运行，正在安装依赖（需要联网）..."
  npm install --no-audit --no-fund || { echo "  依赖安装失败，请确认已装 Node.js 18+"; exit 1; }
fi
echo "  依赖就绪，正在启动服务..."
echo ""
exec node server.mjs
