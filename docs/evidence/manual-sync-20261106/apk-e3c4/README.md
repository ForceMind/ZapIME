# Actual e3c4 APK verification

GitHub artifact13565943719 was downloaded in full (235395045 bytes), its GitHub SHA256 and every ZIP CRC verified. Both product APKs passed the unchanged 18-core-JNI/license/model boundary, official SDK35 aapt2, apksigner v3, and zipalign -P16 checks. The new activities are private and the manifest keeps default platform trust/no cleartext. APK bytes remain in GitHub Actions, not in this repository.

The head e3c4 and actual checkout merge2c617 have the identical tree d242. Development signer details and precise APK hashes are in full-e3c4-apks.json; the key is not a production signing identity. Shared macOS testing failed at the canonical fixture path and is preserved separately; Android build/runtime success is not described as all-platform success.
