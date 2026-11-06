# 使用官方 SDK 工具复核 DEX 契约

对象是固定08源码的完整双ABI artifact13503554301，ARM64 APK SHA256为 `6f6b156c1872de23d76b0981120147143f46bb6f5378bc5ff978a9f8aba73f13`。本轮已实际运行官方Google build-tools35 dexdump，退出0；[原始类输出摘录](full-08e84980-official-dexdump-excerpt.txt)与[工具/输入摘要](full-08e84980-official-dexdump-verification.json)独立保存。自制有界读取器仅辅助核对，不是CI门禁。

从上述已核验APK提取classes.dex，在仓库根目录执行（只读APK并写ignored target，不操作设备）：

```bash
python3 - <<'PYTHON'
from pathlib import Path
from zipfile import ZipFile
p = Path('target/voicekey-full-08e84980-artifacts')
with ZipFile(p / 'arm64-v8a.apk') as apk:
    (p / 'arm64-v8a-classes.dex').write_bytes(apk.read('classes.dex'))
PYTHON
"$ANDROID_SDK_ROOT/build-tools/35.0.0/dexdump" -h target/voicekey-full-08e84980-artifacts/arm64-v8a-classes.dex > target/voicekey-full-08e84980-artifacts/arm64-v8a-dexdump.txt
```

在 `Lorg/zapime/android/NativeVoiceKey;` 类中，官方输出明确显示 `finish` 的 `(J)[B` 为 `STATIC`，而 `(JI)[B` 为 `STATIC NATIVE`。静态DEX契约不证明Rust调用约定、非法budget行为或模型时限；实际API28 JNI边界报告单独保留。
