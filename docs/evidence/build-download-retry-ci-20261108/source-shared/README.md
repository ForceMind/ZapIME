# PR98 source/shared original attempt1 observation

Fixed PR head `fb62ad923f376edb7f1c3ab274fc8095718b7ea7`; actual Actions checkout `0647d0bc6734e915821ccc5ddc9bf8f87bf3c299`. GitHub Git commit reads verify both trees are `f99bbff5cc9ca044e5e9fe190f0f58c243434930`. All five original job logs show that checkout. The full UTF-8 decoded plugin log bytes are retained, including BOM and Windows CRLF; SHA256SUMS records each saved file. No original log is edited or reclassified.

Source run42466578724 is SUCCESS: job128687392092 actually ran30 source release regression tests and134 Rust workspace tests, fmt/strict Clippy, and prepared the exact checkout source archive. The publish job128687751174 is SKIPPED on this PR event; this is no new public release.

Shared run42466578822 is FAILURE overall. Jobs Windows128687393145/macOS128687393218/Ubuntu128687393261 are SUCCESS with131/134/134 Rust tests respectively; all results have0 failed,0 ignored,0 filtered. Three Unix-specific tests are not compiled on Windows, and their exact names are in summary.json. fmt/strict Clippy steps passed; the macOS job also passed its shared iOS simulator library check.

Apple job128687393170 failed in Compile real InputMethodKit bundle. Its fetch_resources GET observed HTTP504 on all3 bounded attempts, logging the two allowed retry decisions before the original HTTPError504 exit1. The log does not name the specific resource request. The fixed build_macos.sh performs fetch_resources before cargo release compilation, IMK clang link and codesign. Consequently this job does not provide a fresh IMK build; subsequent iOS app/extension compilation and XCTest steps did not run. Any always-run packaging/upload result cannot substitute for those skipped checks. The original first failure remains; no job/workflow retry was requested.

These are public synthetic build/test logs and metadata only; no user device, real input, recording, credentials or production service was accessed. This observation does not claim Android, ARM/S25, natural speech, real PostgreSQL transaction or stable signing acceptance.
