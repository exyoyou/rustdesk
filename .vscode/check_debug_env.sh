#!/bin/zsh

# 检查Android Rust调试符号和源码映射

echo "🔍 检查Android Rust调试环境..."

# 检查编译目标
TARGET_DIR="/home/youyou/rustdesk/target/aarch64-linux-android/debug"
if [ -d "$TARGET_DIR" ]; then
    echo "✅ 找到Android调试目标目录: $TARGET_DIR"
    
    # 检查调试符号
    echo ""
    echo "📋 调试符号文件:"
    find "$TARGET_DIR" -name "*.so" -o -name "librustdesk*" | head -5
    
    echo ""
    echo "📋 deps目录内容:"
    ls -la "$TARGET_DIR/deps" | grep -E "(librustdesk|libscrap)" | head -5
else
    echo "❌ 未找到Android调试目标目录"
    echo "请先运行: cargo build --target aarch64-linux-android"
fi

# 检查源码文件
echo ""
echo "📁 源码文件检查:"
CAMERA_FILE="/home/youyou/rustdesk/libs/scrap/src/common/camera.rs"
if [ -f "$CAMERA_FILE" ]; then
    echo "✅ 找到camera.rs: $CAMERA_FILE"
else
    echo "❌ 未找到camera.rs文件"
fi

# 检查Rust toolchain
echo ""
echo "🦀 Rust工具链检查:"
rustup show | grep -A 5 "installed targets"

# 检查Android NDK
echo ""
echo "📱 Android NDK检查:"
if [ -n "$ANDROID_NDK_ROOT" ]; then
    echo "✅ ANDROID_NDK_ROOT: $ANDROID_NDK_ROOT"
else
    echo "⚠️  ANDROID_NDK_ROOT 未设置"
fi

# 检查VS Code配置
echo ""
echo "⚙️  VS Code配置检查:"
if [ -f "/home/youyou/rustdesk/.vscode/settings.json" ]; then
    echo "✅ 找到VS Code设置文件"
    echo "当前target配置:"
    grep "rust-analyzer.cargo.target" /home/youyou/rustdesk/.vscode/settings.json || echo "未找到target配置"
else
    echo "❌ 未找到VS Code设置文件"
fi

echo ""
echo "💡 调试建议:"
echo "1. 确保使用 'Attach Android (Rust Focus)' 调试配置"
echo "2. 在设置断点前，先重新构建Android版本"
echo "3. 如果断点仍然变灰，尝试在lldb控制台运行: image list"
echo "4. 检查源码映射: settings show target.source-map"