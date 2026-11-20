# 原生版构建脚本（Git Bash / MSYS 环境）
# 用法：bash build.sh
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/src"
TC="$ROOT/toolchain"
GO="$TC/go/bin/go.exe"
RSRC="$TC/gopath/bin/rsrc.exe"

export GOCACHE="$TC/gocache"
export GOPATH="$TC/gopath"
export GOPROXY="https://goproxy.cn,direct"
export GOSUMDB=off
export CGO_ENABLED=0

echo "[1/3] 生成资源（manifest + 图标）"
"$RSRC" -manifest "$ROOT/build/glcaliper.manifest" -ico "$ROOT/build/app3.ico" -o "$SRC/glcaliper.syso"

echo "[2/3] 运行测试"
cd "$SRC"
"$GO" test ./protocol/ ./core/

echo "[3/3] 编译单文件 exe（-H=windowsgui 隐藏控制台）"
"$GO" build -ldflags "-H=windowsgui" -o "$ROOT/dist/广陆蓝牙卡尺助手-原生版.exe" .

echo "完成：$ROOT/dist/广陆蓝牙卡尺助手-原生版.exe"
ls -lh "$ROOT/dist/广陆蓝牙卡尺助手-原生版.exe"
