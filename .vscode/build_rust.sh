#!/usr/bin/env bash

SHOULD_BUILD_RUST="$1"
PLATFORM="$2"
ANDROID_TARGET="$3"
echo "编译 $SHOULD_BUILD_RUST-$PLATFORM-$ANDROID_TARGET"

if [ "${SHOULD_BUILD_RUST}" = "true" ]; then
    echo "Compiling Rust code for ${PLATFORM}..."
    # 在这里可以根据 ${PLATFORM} 的值执行不同的 cargo build 命令
    # 例如：
    cargo install flutter_rust_bridge_codegen --version 1.80.1 --features uuid
    # flutter pub get
    ~/.cargo/bin/flutter_rust_bridge_codegen --rust-input ../src/flutter_ffi.rs --dart-output ./lib/generated_bridge.dart --c-output ./macos/Runner/bridge_generated.h
    if [ "${PLATFORM}" = "android" ]; then
        case "$ANDROID_TARGET" in
            arm64)
                echo "ndk 编译 arm64..."
                ./flutter/build_android_deps.sh arm64-v8a
                cargo ndk --platform 21 --target aarch64-linux-android build --features flutter,hwcodec
                mkdir -p ./flutter/android/app/src/main/jniLibs/arm64-v8a
                cp ./target/aarch64-linux-android/debug/liblibrustdesk.so ./flutter/android/app/src/main/jniLibs/arm64-v8a/librustdesk.so
            ;;
            x86_64)
                echo "ndk 编译 x64..."
                ./flutter/build_android_deps.sh x86_64
                cargo ndk --platform 21 --target x86_64-linux-android build --features flutter
                mkdir -p ./flutter/android/app/src/main/jniLibs/x86_64
                cp ./target/x86_64-linux-android/debug/liblibrustdesk.so ./flutter/android/app/src/main/jniLibs/x86_64/librustdesk.so
            ;;
        esac

    elif [ "${PLATFORM}" = "linux" ]; then
        cargo build --features flutter
    fi
else
    echo "Skipping Rust compilation for ${PLATFORM}."
fi