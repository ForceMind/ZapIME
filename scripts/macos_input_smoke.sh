#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
[[ $(uname -s) == Darwin && ${GITHUB_ACTIONS:-} == true ]] || {
  echo 'Ephemeral GitHub macOS runner required; refuses local user input-source changes' >&2
  exit 78
}
destination="$HOME/Library/Input Methods/ZapIME.app"
[[ ! -e "$destination" ]] || { echo 'Refusing existing ZapIME installation' >&2; exit 78; }
probe=target/macos-test/InputProbe.app
mkdir -p "$probe/Contents/MacOS" "$(dirname "$destination")"
cat > "$probe/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>org.zapime.test.InputProbe</string>
<key>CFBundleExecutable</key><string>InputProbe</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleName</key><string>ZapIME Input Probe</string>
<key>NSPrincipalClass</key><string>NSApplication</string>
</dict></plist>
PLIST
xcrun clang++ -std=c++17 -fobjc-arc platforms/macos/tests/NativeInputSmoke.mm \
  -framework AppKit -framework Carbon -o "$probe/Contents/MacOS/InputProbe"
codesign --force --sign - "$probe"
cp -R target/macos/ZapIME.app "$destination"
cleanup() {
  # Only the input-method process installed by this isolated CI job may be terminated.
  /usr/bin/pkill -f "^$destination/Contents/MacOS/ZapIME$" || true
  rm -rf "$destination"
}
trap cleanup EXIT
"$probe/Contents/MacOS/InputProbe" "$destination"
