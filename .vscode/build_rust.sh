#!/usr/bin/env zsh

# Load zsh environment
[[ -f ~/.zshrc ]] && source ~/.zshrc

# Fail fast on any error and propagate failures through pipelines
set -Eeuo pipefail

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
        
        echo -e "\033[32mFlutter 安装完成！\033[0m"
        echo -e "\033[33m建议将以下内容添加到 ~/.zshrc:\033[0m"
        echo 'export PATH="$HOME/flutter/bin:$PATH"'
    else
        echo "Flutter 已安装: $(flutter --version | head -n 1)"
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
        
        echo -e "\033[32mvcpkg 安装完成！\033[0m"
        echo -e "\033[33m建议将以下内容添加到 ~/.zshrc:\033[0m"
        echo 'export VCPKG_ROOT=$HOME/vcpkg'
        echo 'export PATH=$VCPKG_ROOT:$PATH'
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
        
        echo -e "\033[32mRust 安装完成！\033[0m"
        echo "Rust 版本: $(rustc --version)"
        echo -e "\033[33m建议将以下内容添加到 ~/.zshrc:\033[0m"
        echo 'source $HOME/.cargo/env'
    else
        echo "Rust 已安装: $(rustc --version)"
    fi
}

# 缺失依赖安装函数
on_install() {
    echo -e "\n\033[33m正在安装缺失的依赖，可能需要一些时间 (行 ${line})...\033[0m"
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
    
    # 检测并安装 vcpkg
    ensure_vcpkg
    
    # 在这里可以根据 ${PLATFORM} 的值执行不同的 cargo build 命令
    # 例如：
    cargo install flutter_rust_bridge_codegen --version 1.80.1 --features uuid
    # flutter pub get
    
    # 路径修正：VS Code 的 ${workspaceFolder} 不会在 bash 里展开
    # 正确的路径应指向仓库下的 src/flutter_ffi.rs、flutter/lib、flutter/macos/Runner
    RUST_INPUT="./src/flutter_ffi.rs"
    DART_OUT="./flutter/lib/generated_bridge.dart"
    C_OUT="./flutter/macos/Runner/bridge_generated.h"

    # 确保输出目录存在
    mkdir -p "$(dirname "${DART_OUT}")" "$(dirname "${C_OUT}")"
    cd ./flutter
    flutter pub get
    cd ..

    # 基于绝对路径执行 codegen，避免相对路径误判
    ~/.cargo/bin/flutter_rust_bridge_codegen --rust-input "${RUST_INPUT}" --dart-output "${DART_OUT}" --c-output "${C_OUT}"
    
    if [ "${PLATFORM}" = "android" ]; then
        case "$ANDROID_TARGET" in
            arm64)
                echo "ndk 编译 arm64..."
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