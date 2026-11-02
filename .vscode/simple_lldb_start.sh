#!/bin/zsh

# 简化的lldb-server启动脚本
# 使用与手动运行完全相同的格式

set -e

PACKAGE_NAME="com.carriez.flutter_hbb"

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查设备连接
if [ "$(adb devices | grep -v "List of devices" | grep -v "^$" | wc -l)" -eq 0 ]; then
    log_error "未检测到已连接的Android设备"
    exit 1
fi

# 终止已存在的lldb-server
EXISTING_PID=$(adb shell "ps | grep lldb-server | grep -v grep" | awk '{print $2}' | head -1)
if [ ! -z "$EXISTING_PID" ]; then
    log_warn "终止已运行的lldb-server (PID: $EXISTING_PID)"
    adb shell "kill $EXISTING_PID" 2>/dev/null || true
    sleep 2
fi

# 获取应用PID
APP_PID=$(adb shell "ps | grep $PACKAGE_NAME | grep -v grep" | awk '{print $2}' | head -1)
if [ -z "$APP_PID" ]; then
    log_error "未找到应用进程，请确保 $PACKAGE_NAME 正在运行"
    exit 1
fi

log_info "找到应用进程 PID: $APP_PID"

# 使用与您手动运行相同的格式启动lldb-server
log_info "启动lldb-server..."
echo "执行命令: adb shell -> run-as $PACKAGE_NAME -> lldb-server platform --server --listen unix-abstract:///data/data/com.carriez.flutter_hbb/debug.sock"

# 在后台启动lldb-server
(
    adb shell "run-as $PACKAGE_NAME ./lldb-server platform --server --listen unix-abstract:///data/data/com.carriez.flutter_hbb/debug.sock"
) &

LLDB_PID=$!
sleep 3

# 检查lldb-server是否启动成功
NEW_LLDB_PID=$(adb shell "ps | grep lldb-server | grep -v grep" | awk '{print $2}' | head -1)

if [ ! -z "$NEW_LLDB_PID" ]; then
    log_info "✅ lldb-server启动成功 (PID: $NEW_LLDB_PID)"
    echo ""
    echo "=========================================="
    echo "🎯 调试信息:"
    echo "   应用PID: $APP_PID"
    echo "   lldb-server PID: $NEW_LLDB_PID"
    echo "   Socket: unix-abstract:///data/data/com.carriez.flutter_hbb/debug.sock"
    echo ""
    echo "📝 VS Code调试步骤:"
    echo "   1. 选择 'Attach remote android' 配置"
    echo "   2. 输入PID: $APP_PID"
    echo "   3. 开始调试"
    echo "=========================================="
    
    # 保持脚本运行，显示实时状态
    echo ""
    log_info "lldb-server正在运行中... (按Ctrl+C停止)"
    
    # 捕获中断信号，清理进程
    trap 'log_warn "正在停止lldb-server..."; adb shell "kill $NEW_LLDB_PID" 2>/dev/null || true; exit 0' INT TERM
    
    # 等待用户中断
    wait $LLDB_PID
else
    log_error "❌ lldb-server启动失败"
    exit 1
fi