#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -u

output=target/a3-android-oidc
signer="${ANDROID_HOME:?Android SDK not configured}/build-tools/35.0.0/apksigner"
result=0

# Each case runs the isolated-device guard and cleans its own resources. Keep
# reports from later cases while preserving any failure as the job's outcome.
run_case() {
    python3 scripts/android_a3_oidc.py "$output/probe.apk" "$signer" "$@" || result=1
}

run_case "$output/oidc-auth.test"
run_case "$output/oidc-account.test" "$output/main-source/fixture.apk"
run_case "$output/oidc-account.test" "$output/main-source/fixture.apk" "$output/sync-host-fixture"
for case in queue-window archive-origin automatic automatic-recovery; do
    run_case "$output/oidc-account.test" "$output/main-source/fixture.apk" "$output/sync-host-fixture" "$case"
done
exit "$result"
