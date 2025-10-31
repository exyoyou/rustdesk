#!/bin/zsh

# 获取RustDesk Android应用的进程ID
# 这个脚本专门用于获取PID，配合lldb调试使用

set -e

# 配置
PACKAGE_NAME="com.carriez.flutter_hbb"

# 颜色输出
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查ADB
if ! command -v adb &> /dev/null; then
    log_error "未找到adb命令"
    exit 1
fi

# 检查设备连接
if [ "$(adb devices | grep -v "List of devices" | grep -v "^$" | wc -l)" -eq 0 ]; then
    log_error "未检测到已连接的Android设备"
    exit 1
fi

log_info "正在查找RustDesk进程..."

# 查找进程PID
PID=$(adb shell "ps | grep $PACKAGE_NAME | grep -v grep" | awk '{print $2}' | head -1)

# 如果ps命令找不到，尝试pidof
if [ -z "$PID" ]; then
    PID=$(adb shell "pidof $PACKAGE_NAME 2>/dev/null" | awk '{print $1}')
fi

if [ -z "$PID" ]; then
    log_error "未找到RustDesk进程，请确保应用正在运行"
    echo ""
    echo "当前运行的相关进程:"
    adb shell "ps | grep -E '(flutter|rust|carriez)'" || echo "未找到相关进程"
    exit 1
fi

# 验证PID
if ! [[ "$PID" =~ ^[0-9]+$ ]]; then
    log_error "获取到的PID不是有效数字: $PID"
    exit 1
fi

log_info "找到RustDesk进程 PID: $PID"

# 输出PID供其他脚本使用
echo "$PID"