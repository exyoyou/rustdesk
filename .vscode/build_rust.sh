#!/usr/bin/env bash

SHOULD_BUILD_RUST="$1"
PLATFORM="$2"
ANDROID_TARGET="$3"

if [ "${SHOULD_BUILD_RUST}" = "true" ]; then
    echo "Compiling Rust code for ${PLATFORM}..."
    # 在这里可以根据 ${PLATFORM} 的值执行不同的 cargo build 命令
    # 例如：
    cargo install flutter_rust_bridge_codegen --version 1.80.1 --features uuid
    flutter pub get
    ~/.cargo/bin/flutter_rust_bridge_codegen --rust-input ../src/flutter_ffi.rs --dart-output ./lib/generated_bridge.dart --c-output ./macos/Runner/bridge_generated.h
    if [ "${PLATFORM}" = "android" ]; then
        case ${{ ANDROID_TARGET }} in
            arm_64)
                cargo ndk --platform 21 --target aarch64-linux-android build --features flutter,hwcodec
            ;;
            x86_64)
                cargo ndk --platform 21 --target x86_64-linux-android build --features flutter
            ;;
          esac
    elif [ "${PLATFORM}" = "linux" ]; then
        cargo build --features flutter
    fi
else
    echo "Skipping Rust compilation for ${PLATFORM}."
fi