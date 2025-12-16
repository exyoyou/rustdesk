#!/usr/bin/env zsh

# RustDesk 环境配置脚本
# 负责检测和安装所有必要的开发环境依赖

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
            echo "正在克隆 Flutter SDK（版本 3.24.5）..."
            cd "$HOME"
            git clone https://github.com/flutter/flutter.git -b 3.24.5 --depth 1
            
            # 应用 RustDesk 补丁
            echo "应用 Flutter 补丁..."
            cd "$HOME/flutter"
            local WORKSPACE_ROOT="$(cd "$(dirname "${(%):-%x}")/.." && pwd)"
            git apply "${WORKSPACE_ROOT}/.github/patches/flutter_3.24.4_dropdown_menu_enableFilter.diff"
        fi
        
        # 添加到 PATH
        export PATH="$HOME/flutter/bin:$PATH"
        
        # 配置 Flutter 使用 Java 17 JDK
        echo "配置 Flutter 使用 Java 17 JDK..."
        local JAVA17_HOME=""
        if [ -d "/usr/lib/jvm/java-17-openjdk-amd64" ]; then
            JAVA17_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
        elif [ -d "/usr/lib/jvm/java-17-openjdk" ]; then
            JAVA17_HOME="/usr/lib/jvm/java-17-openjdk"
        else
            # 尝试自动查找
            local JAVA17_PATH=$(update-alternatives --list java 2>/dev/null | grep java-17 | head -n 1)
            if [ -n "${JAVA17_PATH}" ]; then
                JAVA17_HOME="${JAVA17_PATH%/bin/java}"
            fi
        fi
        
        if [ -n "${JAVA17_HOME}" ]; then
            flutter config --jdk-dir "${JAVA17_HOME}"
            echo -e "\033[32mFlutter JDK 已配置为: ${JAVA17_HOME}\033[0m"
        else
            echo -e "\033[33m警告: 未找到 Java 17，Flutter 构建可能会失败\033[0m"
            echo "请安装 Java 17: sudo apt install openjdk-17-jdk-headless"
        fi
        
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

# 检查关键系统依赖是否已安装
check_system_deps() {
    local missing_deps=()
    local key_commands=("clang" "cmake" "git" "pkg-config" "nasm")
    
    for cmd in "${key_commands[@]}"; do
        if ! command -v "$cmd" >/dev/null 2>&1; then
            missing_deps+=("$cmd")
        fi
    done
    
    if [ ${#missing_deps[@]} -gt 0 ]; then
        return 1  # 有缺失依赖
    else
        return 0  # 依赖完整
    fi
}

# 安装系统依赖（仅在需要时调用）
install_system_deps() {
    echo -e "\n\033[33m检查系统依赖...\033[0m"
    
    if check_system_deps; then
        echo -e "\033[32m系统依赖已满足\033[0m"
        return 0
    fi
    
    echo -e "\n\033[33m检测到缺失的系统依赖，正在安装（可能需要一些时间）...\033[0m"
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
            libssl-dev
    
    echo -e "\033[32m系统依赖安装完成\033[0m"
}

# 检查所有环境依赖（可选是否安装缺失项）
check_all_environment() {
    local auto_install="${1:-false}"
    
    echo -e "\n\033[36m========== 环境依赖检查 ==========\033[0m"
    
    if [ "${auto_install}" = "true" ]; then
        install_system_deps
        ensure_rust
        ensure_flutter
        ensure_vcpkg
    else
        # 仅检查，不安装
        echo -n "Rust: "
        if command -v rustc >/dev/null 2>&1; then
            echo -e "\033[32m✓\033[0m $(rustc --version)"
        else
            echo -e "\033[31m✗ 未安装\033[0m"
        fi
        
        echo -n "Flutter: "
        if command -v flutter >/dev/null 2>&1; then
            echo -e "\033[32m✓\033[0m $(flutter --version | head -n 1)"
        else
            echo -e "\033[31m✗ 未安装\033[0m"
        fi
        
        echo -n "vcpkg: "
        if command -v vcpkg >/dev/null 2>&1; then
            echo -e "\033[32m✓\033[0m"
        else
            echo -e "\033[31m✗ 未安装\033[0m"
        fi
        
        echo -n "Android SDK: "
        if [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT}}" ]; then
            echo -e "\033[32m✓\033[0m ${ANDROID_HOME:-${ANDROID_SDK_ROOT}}"
        else
            echo -e "\033[31m✗ 未配置\033[0m"
        fi
    fi
    
    echo -e "\033[36m==================================\033[0m\n"
}
