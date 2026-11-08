# Manual sync host checks

These are actual host test/compile outputs for the source hashes in verification.json. No device, Android HTTPS, phone or production deployment success is inferred. The source of the JNI module is compiled and tested directly by an ignored Linux manifest; the three boundary tests are not Android execution. The first policy javac failure is retained alongside the corrected run.

The whole workspace passed 114 tests and strict fmt/Clippy. TLS tests use an isolated host JVM trust fixture; the production Android trust policy remains unchanged. New Android descriptor and transfer reports must be produced and strictly verified by the corresponding commit CI.
