#!/usr/bin/env bash

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

if [ "${SHOULD_BUILD_RUST}" = "true" ]; then
    echo "Compiling Rust code for ${PLATFORM}..."
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