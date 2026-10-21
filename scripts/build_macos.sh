#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
[[ $(uname -s) == Darwin ]] || { echo 'macOS/Xcode required' >&2; exit 1; }
python3 scripts/fetch_resources.py
# Apply the advertised minimum to Rust build scripts too (not only the final host link).
export MACOSX_DEPLOYMENT_TARGET=13.0
cargo build --release --locked -p zapime-core
app=target/macos/ZapIME.app
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources/EngineResources"
cp target/resources/* "$app/Contents/Resources/EngineResources/"
cp platforms/macos/Info.plist "$app/Contents/Info.plist"
cp -R third_party "$app/Contents/Resources/"
cp LICENSE "$app/Contents/Resources/"
rust_target=$(rustc -vV | sed -n 's/^host: //p')
python3 scripts/collect_rust_notices.py "$rust_target" "$app/Contents/Resources/rust-licenses"
xcrun clang++ -std=c++17 -fobjc-arc -mmacosx-version-min=13.0 \
  platforms/macos/ZapInputController.mm target/release/libzapime_core.a \
  -framework AppKit -framework InputMethodKit -framework Security -framework SystemConfiguration \
  -liconv -lc++ -o "$app/Contents/MacOS/ZapIME"
codesign --force --sign - "$app"
codesign --verify --strict "$app"
echo "Built host-architecture development bundle: $app (ad-hoc signed, not notarized)"
