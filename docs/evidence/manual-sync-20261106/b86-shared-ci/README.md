# Shared-core and Apple-host checks for b86e4f2

[Workflow run 42353573085](https://github.com/ForceMind/ZapIME/actions/runs/42353573085)
completed with all four jobs successful. Its head is
`b86e4f27fcce2c5d0bd394b139463bb52ea5fbf9`; the logs identify the tested
PR merge as `e38889af93144f1bd9076fd2723fd0c1d762f457`, into
`e8e016cb6fe932d58cad2b04ed5ed6ab0f89ed9e`.

| Job | Actual test result | Other completed checks |
| --- | --- | --- |
| Shared core, macOS | 118 passed, 0 failed, 0 ignored | Upstream verification, fmt, strict Clippy, iOS simulator shared-library check |
| Shared core, Ubuntu | 118 passed, 0 failed, 0 ignored | Upstream verification, fmt, strict Clippy |
| Shared core, Windows | 115 passed, 0 failed, 0 ignored | Upstream verification, fmt, strict Clippy |
| Apple native development hosts | 3 XCTest cases passed, 0 failed | InputMethodKit bundle, iOS app and keyboard extension, development packaging/upload |

The Windows total is three lower because the two data-writer symlink cases and
the workspace leaf-symlink case use existing `#[cfg(unix)]` boundaries. They are
compiled out on Windows, not ignored failures. The three new encrypted-sync /
real-decoder candidate tests pass on all three platforms. The corrected valid
writer fixture and the new parent-alias rejection/canonical-path acceptance
test pass on macOS. The earlier e3c4 macOS failure remains in
`../macos-canonical-path/`; it has not been replaced or removed.

Apple tests ran on the iPhone 17 Pro **simulator**, iOS 26.4.1, arm64. They are:

- `KeyboardProxyTests.testOwnDocumentNotificationsDoNotDropQueuedEnglishKeys`
- `NativeCoreTests.testBundledOfflinePinyinAndPrivateBoundary`
- `NativeCoreTests.testWorkerKeepsNativeSessionOnOneThread`

The raw log reports `TEST EXECUTE SUCCEEDED`. The macOS development bundle is
ad-hoc signed and not notarized; the iOS app/keyboard build has no distribution
signing. Artifact `13566942345` is reported as 128,833,616 bytes with GitHub
digest `a5f0909f44dff86bbfa954e375e43a38167aad393f63a487d57ca3d456a7f621`.
This folder records its metadata and upload log, not a separately downloaded
and verified archive.

All four complete logs retain the connector's decoded UTF-8 bytes, BOMs and
original line endings, including Windows CRLF. `verification.json` records
their sizes, hashes, test totals, source hashes and completed job steps. One
Apple log retrieval briefly returned `BlobNotFound` while final job completion
was pending; the subsequent complete retrieval succeeded. The observed error
fields are retained in the verification record as a retrieval event, not a
product test failure.

`crates/zapime-core/src/android_sync.rs` is byte-identical between e3c4 and
b86e4f2, including the native sync JNI function bodies. The production
`open_sync_writer` function body is also unchanged; its correction was
documentation and test fixtures. These are source comparisons, not additional
Android JNI execution. The successful jobs establish the scopes above, not
phone testing, activated third-party system-keyboard acceptance, Android HTTPS,
public-server/R2 deployment, notarization or a stable release.
