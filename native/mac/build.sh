#!/bin/bash
# 编译通用(arm64 + x86_64)的 pvm-audiotap，放进模组资源。需要 Xcode 命令行工具。
set -e
cd "$(dirname "$0")"
OUT=../../src/main/resources/native/mac/pvm-audiotap
mkdir -p "$(dirname "$OUT")" /tmp/pvm-build
for arch in arm64 x86_64; do
  swiftc -O -sdk "${HP_SDK:-/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk}" -target $arch-apple-macos14.2 audiotap.swift -o /tmp/pvm-build/pvm-audiotap-$arch \
    -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker Info.plist
done
lipo -create /tmp/pvm-build/pvm-audiotap-arm64 /tmp/pvm-build/pvm-audiotap-x86_64 -output "$OUT"
codesign -s - -f "$OUT"
ls -l "$OUT"
