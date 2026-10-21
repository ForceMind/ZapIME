#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
[[ $(uname -s) == Darwin ]] || { echo 'macOS/Xcode and XcodeGen required' >&2; exit 1; }
mode=${1:-simulator}
case "$mode" in
  simulator)
    sdk=iphonesimulator
    action=build-for-testing
    apple_arch=$(uname -m)
    case $(uname -m) in
      arm64) rust_target=aarch64-apple-ios-sim ;;
      x86_64) rust_target=x86_64-apple-ios ;;
      *) exit 1 ;;
    esac ;;
  device) sdk=iphoneos; rust_target=aarch64-apple-ios; apple_arch=arm64; action=build ;;
  *) echo 'Use simulator or device' >&2; exit 1 ;;
esac
python3 scripts/fetch_resources.py
rustup target add "$rust_target"
SDKROOT=$(xcrun --sdk "$sdk" --show-sdk-path) cargo build --release --locked -p zapime-core --target "$rust_target"
stage=target/apple-ios
mkdir -p "$stage/lib" "$stage/EngineResources" "$stage/Licenses"
cp "target/$rust_target/release/libzapime_core.a" "$stage/lib/"
cp target/resources/* "$stage/EngineResources/"
cp -R third_party "$stage/Licenses/"
cp LICENSE "$stage/Licenses/"
python3 scripts/collect_rust_notices.py "$rust_target" "$stage/Licenses/rust"
xcodegen generate --spec platforms/ios/project.yml
xcodebuild -project platforms/ios/ZapIME.xcodeproj -scheme ZapIME -configuration Debug \
  -sdk "$sdk" -destination "generic/platform=iOS$( [[ $mode == simulator ]] && printf ' Simulator' )" \
  -derivedDataPath target/ios-xcode CODE_SIGNING_ALLOWED=NO ARCHS="$apple_arch" ONLY_ACTIVE_ARCH=YES "$action"
echo 'Native iOS app and keyboard extension compiled without distribution signing.'
