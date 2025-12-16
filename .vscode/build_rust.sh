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

# 加载环境配置脚本
source "${SCRIPT_DIR}/setup_env.sh"

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

if [ "${SHOULD_BUILD_RUST}" = "true" ]; then
    echo "Compiling Rust code for ${PLATFORM}..."
    
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
    
    # 检测并安装 flutter_rust_bridge_codegen
    if ! command -v flutter_rust_bridge_codegen >/dev/null 2>&1 && [ ! -f "$HOME/.cargo/bin/flutter_rust_bridge_codegen" ]; then
        echo "安装 flutter_rust_bridge_codegen..."
        cargo install flutter_rust_bridge_codegen --version 1.80.1 --features uuid
    else
        echo "flutter_rust_bridge_codegen 已安装"
    fi
    
    # 路径修正：使用绝对路径避免目录切换问题
    RUST_INPUT="${WORKSPACE_ROOT}/src/flutter_ffi.rs"
    DART_OUT="${WORKSPACE_ROOT}/flutter/lib/generated_bridge.dart"
    C_OUT="${WORKSPACE_ROOT}/flutter/macos/Runner/bridge_generated.h"

    # 确保输出目录存在
    mkdir -p "$(dirname "${DART_OUT}")" "$(dirname "${C_OUT}")"
    
    # 检查是否需要运行 flutter pub get（检查 pubspec.lock 是否存在且最新）
    if [ ! -f "${WORKSPACE_ROOT}/flutter/pubspec.lock" ] || [ "${WORKSPACE_ROOT}/flutter/pubspec.yaml" -nt "${WORKSPACE_ROOT}/flutter/pubspec.lock" ]; then
        echo "运行 flutter pub get..."
        (cd "${WORKSPACE_ROOT}/flutter" && flutter pub get)
    else
        echo "Flutter 依赖已是最新"
    fi

    # 检查是否需要重新生成 bridge 代码
    NEED_CODEGEN=false
    if [ ! -f "${DART_OUT}" ] || [ "${RUST_INPUT}" -nt "${DART_OUT}" ]; then
        NEED_CODEGEN=true
    fi
    
    if [ "${NEED_CODEGEN}" = true ]; then
        echo "运行 flutter_rust_bridge_codegen..."
        cd "${WORKSPACE_ROOT}"
        ~/.cargo/bin/flutter_rust_bridge_codegen --rust-input "${RUST_INPUT}" --dart-output "${DART_OUT}" --c-output "${C_OUT}"
    else
        echo "Bridge 代码已是最新，跳过 codegen"
    fi
    
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
                
                # 检查是否需要重新编译
                TARGET_SO="./target/aarch64-linux-android/debug/liblibrustdesk.so"
                NEED_BUILD=false
                
                if [ ! -f "${TARGET_SO}" ]; then
                    echo "目标 .so 文件不存在，需要编译"
                    NEED_BUILD=true
                else
                    # 检查是否有比 .so 更新的 Rust 源文件
                    if [ -n "$(find src libs -name '*.rs' -newer "${TARGET_SO}" 2>/dev/null)" ]; then
                        echo "检测到 Rust 源代码变化，需要重新编译"
                        NEED_BUILD=true
                    else
                        echo -e "\033[32m未检测到源代码变化，跳过编译（使用缓存的 .so）\033[0m"
                    fi
                fi
                
                if [ "${NEED_BUILD}" = true ]; then
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
                fi
                
                # 检查并复制 .so 文件到 jniLibs
                TARGET_DIR="./flutter/android/app/src/main/jniLibs/arm64-v8a"
                TARGET_LIB="${TARGET_DIR}/librustdesk.so"
                TARGET_CPP="${TARGET_DIR}/libc++_shared.so"
                NEED_COPY=false
                
                mkdir -p "${TARGET_DIR}"
                
                if [ ! -f "${TARGET_LIB}" ] || [ "${TARGET_SO}" -nt "${TARGET_LIB}" ]; then
                    NEED_COPY=true
                fi
                
                if [ "${NEED_COPY}" = true ]; then
                    echo "复制 .so 文件到 Flutter 项目中..."
                    cp "${TARGET_SO}" "${TARGET_LIB}"
                    cp ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so "${TARGET_CPP}"
                    echo "复制完成"
                else
                    echo -e "\033[32mjniLibs 中的 .so 已是最新，跳过复制\033[0m"
                fi
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
                
                # 检查是否需要重新编译
                TARGET_SO="./target/x86_64-linux-android/debug/liblibrustdesk.so"
                NEED_BUILD=false
                
                if [ ! -f "${TARGET_SO}" ]; then
                    echo "目标 .so 文件不存在，需要编译"
                    NEED_BUILD=true
                else
                    # 检查是否有比 .so 更新的 Rust 源文件
                    if [ -n "$(find src libs -name '*.rs' -newer "${TARGET_SO}" 2>/dev/null)" ]; then
                        echo "检测到 Rust 源代码变化，需要重新编译"
                        NEED_BUILD=true
                    else
                        echo -e "\033[32m未检测到源代码变化，跳过编译（使用缓存的 .so）\033[0m"
                    fi
                fi
                
                if [ "${NEED_BUILD}" = true ]; then
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
                fi
                
                # 检查并复制 .so 文件到 jniLibs
                TARGET_DIR="./flutter/android/app/src/main/jniLibs/x86_64"
                TARGET_LIB="${TARGET_DIR}/librustdesk.so"
                TARGET_CPP="${TARGET_DIR}/libc++_shared.so"
                NEED_COPY=false
                
                mkdir -p "${TARGET_DIR}"
                
                if [ ! -f "${TARGET_LIB}" ] || [ "${TARGET_SO}" -nt "${TARGET_LIB}" ]; then
                    NEED_COPY=true
                fi
                
                if [ "${NEED_COPY}" = true ]; then
                    echo "复制 .so 文件到 Flutter 项目中..."
                    cp "${TARGET_SO}" "${TARGET_LIB}"
                    cp ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/x86_64-linux-android/libc++_shared.so "${TARGET_CPP}"
                    echo "复制完成"
                else
                    echo -e "\033[32mjniLibs 中的 .so 已是最新，跳过复制\033[0m"
                fi
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