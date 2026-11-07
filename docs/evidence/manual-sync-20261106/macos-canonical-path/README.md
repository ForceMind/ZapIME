# Canonical fixture paths and retained macOS failure

This evidence retains the actual failed shared-core macOS job for PR #75 head
`e3c4da10b5f90917c2c8d17aeb8fc382f78ec4f9`. Run `42351436378`
checked out merge `2c61712f9414f40f66017aeb1448642c1f5abb89` (head merged
into `e8e016cb6fe932d58cad2b04ed5ed6ab0f89ed9e`). Job `128340247337`
failed in `cargo test --workspace --locked`; Windows and Ubuntu shared-core
jobs and the independent Apple native-host job succeeded. The run exposes only
the successful Apple artifact, not a failure-test artifact.

The original log is copied byte-for-byte from the connector's decoded UTF-8
content, including its BOM and final newline. Its exact byte attributes and SHA
are recorded in `verification.json`; the unaltered connector response and job /
artifact metadata are also retained. Original log lines 677–678 report
`sync.rs:663`, `CannotOpen`, extended code `1550`, for an existing database
under `/var/folders/...`. This is `SQLITE_CANTOPEN_SYMLINK`, not an expired
SQLite VM budget. The first valid-writer open failed before the fixture could
reach its foreign-key, busy-budget and schema assertions.

The Linux counterexample uses the already compiled real `zapime_data::Store`:
the final database is a regular file but a parent is a symbolic link. The same
writer returns code 1550 for that path and accepts its canonical physical path.
The reproducer source and complete output are retained. macOS temporary paths
use the `/var` alias of `/private/var`, which explains the platform-specific
fixture failure.

The correction keeps `SQLITE_OPEN_NOFOLLOW` and the production writer body
unchanged. The valid unit fixture and the new synchronized-candidate fixture
resolve only their known, newly created fixture databases to physical paths.
A new Unix regression explicitly requires the direct parent-alias path to fail
with code 1550 and its canonical existing database to open successfully, without
changing database bytes. The existing leaf-symlink rejection and schema,
foreign-key, lock-wait and VM-budget assertions remain. The API documentation
now requires canonical ancestors; the existing Android JNI already resolves
its root and rejects root/data leaf symlinks. No production alias fallback or
permission relaxation was introduced. The separately owned command-line
fixture canonicalizes its owned parent and preserves the database leaf.

The corrected Linux workspace passed 118 tests: the earlier 114, three real
decoder / encrypted-sync candidate tests, and one new Unix parent-alias test.
Formatting and strict workspace Clippy passed. The actual `android_sync.rs`
module also passed its three host boundary tests, typecheck and strict Clippy
using the retained ignored-manifest content. Source hashes, commands and exact
test totals are in `verification.json`. These local checks are not a successful
macOS rerun, Android execution, HTTPS test, phone test or live candidate-cache
refresh. A corrected commit's macOS CI must establish the macOS result.
