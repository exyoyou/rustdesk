#!/usr/bin/env zsh

# Load zsh environment (only if running in zsh)
if [ -n "${ZSH_VERSION:-}" ]; then
    [[ -f ~/.zshrc ]] && source ~/.zshrc
fi

# Fail fast on any error and propagate failures through pipelines
set -Eeuo pipefail

# Ensure we're in the workspace root directory
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORKSPACE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${WORKSPACE_ROOT}"
echo "Working directory: ${WORKSPACE_ROOT}"

# 初始化并更新 git submodule
if [ -d ".git" ]; then
    echo -e "\n\033[33m检查 git submodule...\033[0m"
    if [ -f ".gitmodules" ]; then
        echo "初始化并更新 submodule..."
        git submodule update --init --recursive
        echo -e "\033[32mSubmodule 更新完成\033[0m"
    else
        echo "未检测到 .gitmodules 文件，跳过 submodule 初始化"
    fi
else
    echo -e "\033[33m警告: 不是 git 仓库，跳过 submodule 检查\033[0m"
fi

# When running from VS Code task, keep the terminal open on failure
on_error() {
    local line="$1"
    local code="${2:-1}"
    echo -e "\n\033[31m构建失败 (exit=${code})，出错位置: 行 ${line}\033[0m"
    if [[ "${VSCODE_TASK:-}" == "1" ]]; then
        # 在 VS Code 任务中：提示并等待任意键后关闭
        echo -e "\n按任意键关闭该终端..."
        # -n 1 读取一个按键；-s 静默不回显；-r 原样读取
        read -n 1 -s -r || true
        echo
        exit "${code}"
    else
        exit "${code}"
    fi
}

trap 'on_error ${LINENO} $?' ERR

SHOULD_BUILD_RUST="$1"
PLATFORM="$2"
ANDROID_TARGET="$3"

echo "编译 $SHOULD_BUILD_RUST-$PLATFORM-$ANDROID_TARGET"

# 简单的工具检查与辅助函数
ensure_cmd() {
    local cmd="$1"
    local hint="${2:-}"
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo -e "\n\033[31m缺少依赖命令: $cmd\033[0m"
        if [[ -n "$hint" ]]; then
            echo -e "安装提示: $hint"
        fi
        return 1
    fi
}

# 检测并安装 Flutter
ensure_flutter() {
    if ! command -v flutter >/dev/null 2>&1; then
        echo -e "\n\033[33m未检测到 Flutter，开始安装到 $HOME/flutter...\033[0m"
        
        # 安装必要依赖
        if ! command -v git >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1; then
            echo "安装基础依赖..."
            sudo apt update
            sudo apt install -y git curl unzip xz-utils zip libglu1-mesa
        fi
        
        # 克隆 Flutter SDK
        if [ ! -d "$HOME/flutter" ]; then
            echo "正在克隆 Flutter SDK（稳定版）..."
            cd "$HOME"
            git clone https://github.com/flutter/flutter.git -b stable --depth 1
        fi
        
        # 添加到 PATH
        export PATH="$HOME/flutter/bin:$PATH"
        
        # 运行 flutter doctor 初始化
        echo "初始化 Flutter..."
        flutter doctor
        
        # 自动写入 ~/.zshrc
        if ! grep -q 'HOME/flutter/bin' ~/.zshrc 2>/dev/null; then
            echo '' >> ~/.zshrc
            echo '# Flutter SDK' >> ~/.zshrc
            echo 'export PATH="$HOME/flutter/bin:$PATH"' >> ~/.zshrc
            echo -e "\033[32mFlutter PATH 已添加到 ~/.zshrc\033[0m"
        fi
        
        echo -e "\033[32mFlutter 安装完成！\033[0m"
    else
        echo "Flutter 已安装: $(flutter --version | head -n 1)"
    fi
}

# 检测并配置 Android SDK
ensure_android_sdk() {
    if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
        echo -e "\n\033[33m警告: 未检测到 ANDROID_HOME 环境变量\033[0m"
        
        # 尝试从 ~/.zshrc 中加载
        if grep -q 'ANDROID_HOME' ~/.zshrc 2>/dev/null; then
            echo "检测到 ~/.zshrc 中有 ANDROID_HOME 配置，正在重新加载..."
            source ~/.zshrc
            
            if [ -z "${ANDROID_HOME:-}" ]; then
                echo -e "\033[31m错误: ~/.zshrc 中的 ANDROID_HOME 路径可能不正确\033[0m"
                echo "请检查并修改为实际的 Android SDK 路径"
                echo "示例: export ANDROID_HOME=\$HOME/Android/Sdk"
            else
                echo -e "\033[32mANDROID_HOME 已加载: ${ANDROID_HOME}\033[0m"
            fi
        else
            echo -e "\033[33m请在 ~/.zshrc 中配置 Android SDK:\033[0m"
            echo ""
            echo "# Android SDK"
            echo "export ANDROID_HOME=\$HOME/Android/Sdk  # 改为你的实际路径"
            echo "export PATH=\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin"
            echo "export PATH=\$PATH:\$ANDROID_HOME/platform-tools"
            echo ""
            echo "然后运行: source ~/.zshrc"
        fi
    else
        echo -e "\033[32mAndroid SDK 已配置: ${ANDROID_HOME:-${ANDROID_SDK_ROOT}}\033[0m"
    fi
    
    # 检查并接受 Android licenses
    if [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT}}" ]; then
        echo -e "\n\033[33m检查 Android licenses...\033[0m"
        
        # 检查 sdkmanager 是否可用
        local SDKMANAGER=""
        if [ -f "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" ]; then
            SDKMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"
        elif [ -f "${ANDROID_HOME}/tools/bin/sdkmanager" ]; then
            SDKMANAGER="${ANDROID_HOME}/tools/bin/sdkmanager"
        fi
        
        if [ -n "${SDKMANAGER}" ]; then
            # 自动接受所有 licenses
            echo "自动接受 Android SDK licenses..."
            yes | "${SDKMANAGER}" --licenses > /dev/null 2>&1 || true
            echo -e "\033[32mAndroid licenses 已接受\033[0m"
        else
            echo -e "\033[33m警告: 未找到 sdkmanager，跳过 licenses 检查\033[0m"
            echo "如果构建失败，请手动运行: flutter doctor --android-licenses"
        fi
    fi
    
    # 检测 Android NDK
    if [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT}}" ]; then
        local SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT}}"
        
        if [ -z "${ANDROID_NDK_HOME:-}" ]; then
            echo -e "\n\033[33m检查 Android NDK...\033[0m"
            
            # 尝试从 SDK 目录查找 NDK
            if [ -d "${SDK_ROOT}/ndk" ]; then
                # 查找最新的 NDK 版本
                local NDK_VERSION=$(ls -1 "${SDK_ROOT}/ndk" 2>/dev/null | sort -V | tail -n 1)
                if [ -n "${NDK_VERSION}" ]; then
                    export ANDROID_NDK_HOME="${SDK_ROOT}/ndk/${NDK_VERSION}"
                    export ANDROID_NDK="${ANDROID_NDK_HOME}"  # 某些工具需要这个变量
                    echo -e "\033[32m自动检测到 NDK (仅本次使用): ${ANDROID_NDK_HOME}\033[0m"
                    
                    # 验证 NDK 工具链是否存在
                    if [ ! -d "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64" ]; then
                        echo -e "\033[31m错误: NDK 工具链目录不存在\033[0m"
                        echo "期望路径: ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64"
                        return 1
                    fi
                else
                    echo -e "\033[31m错误: ${SDK_ROOT}/ndk 目录存在但为空\033[0m"
                    echo "请使用 Android Studio SDK Manager 安装 NDK，或者："
                    echo "sdkmanager --install 'ndk;26.1.10909125'  # 安装指定版本"
                    return 1
                fi
            else
                echo -e "\033[31m未找到 NDK 目录: ${SDK_ROOT}/ndk\033[0m"
                echo "请安装 Android NDK："
                echo "1. 通过 Android Studio SDK Manager 安装"
                echo "2. 或命令行: sdkmanager --install 'ndk;26.1.10909125'"
                return 1
            fi
        else
            echo -e "\033[32mAndroid NDK 已配置: ${ANDROID_NDK_HOME}\033[0m"
        fi
    fi
}

# 检测并安装 vcpkg
ensure_vcpkg() {
    if ! command -v vcpkg >/dev/null 2>&1; then
        echo -e "\n\033[33m未检测到 vcpkg，开始安装到 $HOME/vcpkg...\033[0m"
        
        # 安装必要依赖
        if ! command -v git >/dev/null 2>&1; then
            echo "安装基础依赖..."
            sudo apt update
            sudo apt install -y git curl zip unzip tar cmake ninja-build pkg-config
        fi
        
        # 克隆 vcpkg
        if [ ! -d "$HOME/vcpkg" ]; then
            echo "正在克隆 vcpkg..."
            cd "$HOME"
            git clone https://github.com/microsoft/vcpkg.git
        fi
        
        # 运行 bootstrap
        echo "初始化 vcpkg..."
        cd "$HOME/vcpkg"
        ./bootstrap-vcpkg.sh
        
        # 添加到 PATH
        export VCPKG_ROOT="$HOME/vcpkg"
        export PATH="$VCPKG_ROOT:$PATH"
        
        # 自动写入 ~/.zshrc
        if ! grep -q 'VCPKG_ROOT' ~/.zshrc 2>/dev/null; then
            echo '' >> ~/.zshrc
            echo '# vcpkg' >> ~/.zshrc
            echo 'export VCPKG_ROOT=$HOME/vcpkg' >> ~/.zshrc
            echo 'export PATH=$VCPKG_ROOT:$PATH' >> ~/.zshrc
            echo -e "\033[32mvcpkg 环境变量已添加到 ~/.zshrc\033[0m"
        fi
        
        echo -e "\033[32mvcpkg 安装完成！\033[0m"
    else
        echo "vcpkg 已安装: $(vcpkg version 2>/dev/null || echo 'installed')"
    fi
}

# 检测并安装 Rust
ensure_rust() {
    if ! command -v rustc >/dev/null 2>&1 || ! command -v cargo >/dev/null 2>&1; then
        echo -e "\n\033[33m未检测到 Rust，开始安装 Rust 工具链...\033[0m"
        
        # 安装必要依赖
        if ! command -v curl >/dev/null 2>&1; then
            echo "安装基础依赖..."
            sudo apt update
            sudo apt install -y curl build-essential
        fi
        
        # 安装 Rust
        echo "正在下载并安装 rustup..."
        curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
        
        # 加载 Cargo 环境
        if [ -f "$HOME/.cargo/env" ]; then
            source "$HOME/.cargo/env"
        fi
        
        # 自动写入 ~/.zshrc
        if ! grep -q '.cargo/env' ~/.zshrc 2>/dev/null; then
            echo '' >> ~/.zshrc
            echo '# Rust' >> ~/.zshrc
            echo 'source $HOME/.cargo/env' >> ~/.zshrc
            echo -e "\033[32mRust 环境已添加到 ~/.zshrc\033[0m"
        fi
        
        echo -e "\033[32mRust 安装完成！\033[0m"
        echo "Rust 版本: $(rustc --version)"
    else
        echo "Rust 已安装: $(rustc --version)"
    fi
}

# 缺失依赖安装函数
on_install() {
    echo -e "\n\033[33m正在安装缺失的依赖，可能需要一些时间...\033[0m"
    sudo apt update
    sudo apt install -y \
            clang \
            cmake \
            curl \
            gcc-multilib \
            git \
            g++ \
            g++-multilib \
            libayatana-appindicator3-dev \
            libasound2-dev \
            libc6-dev \
            libclang-dev \
            libunwind-dev \
            libgstreamer1.0-dev \
            libgstreamer-plugins-base1.0-dev \
            libgtk-3-dev \
            libpam0g-dev \
            libpulse-dev \
            libva-dev \
            libxcb-randr0-dev \
            libxcb-shape0-dev \
            libxcb-xfixes0-dev \
            libxdo-dev \
            libxfixes-dev \
            llvm-dev \
            nasm \
            ninja-build \
            openjdk-17-jdk-headless \
            pkg-config \
            tree \
            wget \
            pkg-config \
            libssl-dev
}

if [ "${SHOULD_BUILD_RUST}" = "true" ]; then
    echo "Compiling Rust code for ${PLATFORM}..."
    
    # 安装缺失依赖
    on_install

    # 检测并安装 Rust
    ensure_rust
    
    # 检测并安装 Flutter
    ensure_flutter
    
    # 检测并配置 Android SDK（Android 平台构建需要）
    if [ "${PLATFORM}" = "android" ]; then
        ensure_android_sdk
    fi
    
    # 检测并安装 vcpkg
    ensure_vcpkg
    
    # 在这里可以根据 ${PLATFORM} 的值执行不同的 cargo build 命令
    # 例如：
    cargo install flutter_rust_bridge_codegen --version 1.80.1 --features uuid
    # flutter pub get
    
    # 路径修正：使用绝对路径避免目录切换问题
    RUST_INPUT="${WORKSPACE_ROOT}/src/flutter_ffi.rs"
    DART_OUT="${WORKSPACE_ROOT}/flutter/lib/generated_bridge.dart"
    C_OUT="${WORKSPACE_ROOT}/flutter/macos/Runner/bridge_generated.h"

    # 确保输出目录存在
    mkdir -p "$(dirname "${DART_OUT}")" "$(dirname "${C_OUT}")"
    
    # 在 flutter 目录中运行 pub get
    (cd "${WORKSPACE_ROOT}/flutter" && flutter pub get)

    # 基于绝对路径执行 codegen，从 WORKSPACE_ROOT 运行
    cd "${WORKSPACE_ROOT}"
    ~/.cargo/bin/flutter_rust_bridge_codegen --rust-input "${RUST_INPUT}" --dart-output "${DART_OUT}" --c-output "${C_OUT}"
    
    if [ "${PLATFORM}" = "android" ]; then
        case "$ANDROID_TARGET" in
            arm64)
                echo "ndk 编译 arm64..."
                
                # 确保 NDK 环境变量已设置
                if [ -z "${ANDROID_NDK_HOME:-}" ]; then
                    echo -e "\033[31m错误: ANDROID_NDK_HOME 未设置\033[0m"
                    echo "请确保已正确配置 Android NDK"
                    exit 1
                fi
                
                echo "使用 NDK: ${ANDROID_NDK_HOME}"
                
                ./flutter/build_android_deps.sh arm64-v8a
                # 预检: cargo-ndk 与 rustup 目标
                ensure_cmd cargo "请先安装 Rust 工具链" >/dev/null
                ensure_cmd cargo-ndk "cargo install cargo-ndk" || cargo install cargo-ndk
                if ! rustup target list --installed | grep -q '^aarch64-linux-android$'; then
                    echo "安装 Rust 目标 aarch64-linux-android..."
                    rustup target add aarch64-linux-android
                fi

                echo "[Android aarch64] 开始 cargo ndk 构建 (可能较久)..."
                cargo ndk --platform 21 --target aarch64-linux-android build --features flutter,hwcodec --verbose
                echo "[Android aarch64] 构建完成"
                mkdir -p ./flutter/android/app/src/main/jniLibs/arm64-v8a
                echo "移动 liblibrustdesk.o.so 到 flutter 项目中..."
                cp ./target/aarch64-linux-android/debug/liblibrustdesk.so ./flutter/android/app/src/main/jniLibs/arm64-v8a/librustdesk.so
                cp ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so ./flutter/android/app/src/main/jniLibs/arm64-v8a/
                
                echo "移动完成"
            ;;
            x86_64)
                echo "ndk 编译 x64..."
                
                # 确保 NDK 环境变量已设置
                if [ -z "${ANDROID_NDK_HOME:-}" ]; then
                    echo -e "\033[31m错误: ANDROID_NDK_HOME 未设置\033[0m"
                    echo "请确保已正确配置 Android NDK"
                    exit 1
                fi
                
                echo "使用 NDK: ${ANDROID_NDK_HOME}"
                
                ./flutter/build_android_deps.sh x86_64
                ensure_cmd cargo "请先安装 Rust 工具链" >/dev/null
                ensure_cmd cargo-ndk "cargo install cargo-ndk" || cargo install cargo-ndk
                if ! rustup target list --installed | grep -q '^x86_64-linux-android$'; then
                    echo "安装 Rust 目标 x86_64-linux-android..."
                    rustup target add x86_64-linux-android
                fi

                echo "[Android x86_64] 开始 cargo ndk 构建 (可能较久)..."
                cargo ndk --platform 21 --target x86_64-linux-android build --features flutter --verbose
                echo "[Android x86_64] 构建完成"
                mkdir -p ./flutter/android/app/src/main/jniLibs/x86_64
                echo "移动 liblibrustdesk.o.so 到 flutter 项目中..."
                cp ./target/x86_64-linux-android/debug/liblibrustdesk.so ./flutter/android/app/src/main/jniLibs/x86_64/librustdesk.so
                cp ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/x86_64-linux-android/libc++_shared.so ./flutter/android/app/src/main/jniLibs/x86_64/
                echo "移动完成"
            ;;
        esac

    elif [ "${PLATFORM}" = "linux" ]; then
        echo "Linux 平台编译..."
        cargo build --features flutter
        echo "Linux 平台编译完成."
    fi
else
    echo "Skipping Rust compilation for ${PLATFORM}."
fi