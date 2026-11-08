#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
build=target/android-candidate-ranking-tests
assets=target/candidate-assets-smoke/candidate-model
python3 scripts/prepare_candidate_model.py --check-catalog
python3 scripts/prepare_candidate_model.py --destination "$assets"
mkdir -p "$build"
"${JAVAC:-javac}" --release 8 -encoding UTF-8 -d "$build" \
  platforms/android/java/org/zapime/android/{CandidateRankingContext,CandidateRankingWorker,CandidateRankingResources,CandidateModelCatalog}.java \
  platforms/android/tests/jvm/{CandidateRankingContextSmoke,CandidateRankingWorkerSmoke,CandidateRankingResourcesSmoke}.java
"${JAVA:-java}" -cp "$build" org.zapime.android.CandidateRankingContextSmoke
"${JAVA:-java}" -cp "$build" org.zapime.android.CandidateRankingWorkerSmoke
"${JAVA:-java}" -cp "$build" org.zapime.android.CandidateRankingResourcesSmoke "$assets"
