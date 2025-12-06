#!/bin/zsh

# 在Android设备上启动lldb-server用于远程调试
# 此脚本会检查应用目录中是否有lldb-server，如果没有则从/data/local/tmp复制

set -e

# 配置
PACKAGE_NAME="com.carriez.flutter_hbb"
LLDB_SERVER_BINARY="lldb-server"
SOCKET_NAME="/data/data/com.carriez.flutter_hbb/debug.sock"
TMP_PATH="/data/local/tmp"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 日志函数
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查ADB是否可用
if ! command -v adb &> /dev/null; then
    log_error "未找到adb命令，请确保Android SDK已正确安装并添加到PATH中"
    exit 1
fi

# 检查设备连接
DEVICE_COUNT=$(adb devices | grep -v "List of devices" | grep -v "^$" | wc -l)
if [ "$DEVICE_COUNT" -eq 0 ]; then
    log_error "未检测到已连接的Android设备"
    echo "请确保:"
    echo "1. Android设备已连接"
    echo "2. 已启用USB调试"
    echo "3. 已授权调试连接"
    exit 1
fi

# 如果有多个设备，显示设备列表
if [ "$DEVICE_COUNT" -gt 1 ]; then
    log_warn "检测到多个设备:"
    adb devices
    echo ""
fi

log_info "正在检查应用是否已安装..."

# 检查应用是否已安装
if ! adb shell "pm list packages | grep -q $PACKAGE_NAME"; then
    log_error "应用 $PACKAGE_NAME 未安装"
    exit 1
fi

log_info "应用已安装，正在检查应用目录中的lldb-server..."

# 获取应用数据目录
APP_DATA_DIR="/data/data/$PACKAGE_NAME"
LLDB_SERVER_PATH="$APP_DATA_DIR/$LLDB_SERVER_BINARY"

# 检查应用目录中是否存在lldb-server
LLDB_EXISTS=$(adb shell "run-as $PACKAGE_NAME test -f $LLDB_SERVER_BINARY && echo 'exists' || echo 'not_found'" 2>/dev/null || echo 'not_found')

if [ "$LLDB_EXISTS" = "exists" ]; then
    log_info "在应用目录中找到lldb-server"
else
    log_warn "应用目录中未找到lldb-server，正在从 $TMP_PATH 复制..."
    
    # 检查/data/local/tmp中是否存在lldb-server
    if ! adb shell "test -f $TMP_PATH/$LLDB_SERVER_BINARY"; then
        log_error "在 $TMP_PATH 中未找到 $LLDB_SERVER_BINARY"
        echo "请确保lldb-server已经推送到设备的 $TMP_PATH 目录中"
        echo "可以使用以下命令推送:"
        echo "adb push <NDK_PATH>/toolchains/llvm/prebuilt/linux-x86_64/lib64/clang/<VERSION>/lib/linux/lldb-server $TMP_PATH/"
        exit 1
    fi
    
    log_info "正在复制lldb-server到应用目录..."
    
    # 复制lldb-server到应用目录
    if ! adb shell "run-as $PACKAGE_NAME cp $TMP_PATH/$LLDB_SERVER_BINARY $LLDB_SERVER_BINARY" 2>/dev/null; then
        log_error "复制lldb-server失败"
        exit 1
    fi
    
    # 设置执行权限
    if ! adb shell "run-as $PACKAGE_NAME chmod +x $LLDB_SERVER_BINARY" 2>/dev/null; then
        log_error "设置lldb-server执行权限失败"
        exit 1
    fi
    
    log_info "lldb-server复制成功"
fi

# 检查是否已有lldb-server进程在运行
log_info "检查是否有lldb-server进程正在运行..."
EXISTING_PID=$(adb shell "ps | grep lldb-server | grep -v grep" | awk '{print $2}' | head -1)

if [ ! -z "$EXISTING_PID" ]; then
    log_warn "发现已运行的lldb-server进程 (PID: $EXISTING_PID)，正在终止..."
    adb shell "kill $EXISTING_PID" 2>/dev/null || true
    sleep 2
fi

# 清理可能存在的socket
log_info "清理旧的socket连接..."
adb shell "run-as $PACKAGE_NAME rm -f debug.sock" 2>/dev/null || true

log_info "正在启动lldb-server..."

# 启动lldb-server
# 使用unix abstract socket (适用于非root设备)
log_info "正在启动lldb-server..."

# 清理旧的端口转发
adb forward --remove-all 2>/dev/null || true

# 启动lldb-server使用unix abstract socket
# 正确的命令格式，基于手动运行成功的例子
log_info "启动lldb-server监听 unix-abstract://$SOCKET_NAME..."
adb shell "run-as $PACKAGE_NAME ./lldb-server platform --server --listen unix-abstract://$SOCKET_NAME" &

# 等待服务器启动
sleep 3

# 验证lldb-server是否成功启动
NEW_PID=$(adb shell "ps | grep lldb-server | grep -v grep" | awk '{print $2}' | head -1)

if [ ! -z "$NEW_PID" ]; then
    log_info "lldb-server启动成功 (PID: $NEW_PID)"
    log_info "监听地址: unix-abstract://$SOCKET_NAME"
    
    # 显示进程详细信息
    echo ""
    echo "进程详细信息:"
    adb shell "ps | grep $NEW_PID" || true
    
    echo ""
    log_info "现在可以在VS Code中启动调试会话"
    log_info "使用配置: 'Attach remote android'"
    
    # 获取应用PID供调试使用
    APP_PID=$(adb shell "ps | grep $PACKAGE_NAME | grep -v grep" | awk '{print $2}' | head -1)
    if [ ! -z "$APP_PID" ]; then
        echo ""
        echo "=========================================="
        echo "应用进程PID: $APP_PID"
        echo "Socket路径: unix-abstract://$SOCKET_NAME"
        echo "请复制此PID用于VS Code调试配置"
        echo "=========================================="
    fi
    
    # 导出PID供其他脚本使用
    export ANDROID_PID="$APP_PID"
    echo "ANDROID_PID=$APP_PID"
    echo "SOCKET_PATH=unix-abstract://$SOCKET_NAME"
else
    log_error "lldb-server启动失败"
    echo ""
    echo "调试信息:"
    echo "检查应用进程:"
    adb shell "ps | grep $PACKAGE_NAME" || echo "未找到应用进程"
    echo ""
    echo "检查lldb-server文件:"
    adb shell "run-as $PACKAGE_NAME ls -la $LLDB_SERVER_BINARY" || echo "lldb-server文件不存在或无权限"
    exit 1
fi