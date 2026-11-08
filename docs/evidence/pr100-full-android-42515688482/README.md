# PR100 固定源码完整 Android 原件复验

2026-11-08 UTC 回读 [完整 Android 42515688482 attempt 1](https://github.com/ForceMind/ZapIME/actions/runs/42515688482)：六个作业最终均为 SUCCESS。PR head 为 `4c98059dc95c56bd46cf568ec9189b1bdbfae591`，六个 producer 的实际 checkout 均为 `c50e375966c302598dcc23dc2cc032e2ae07cb5c`；Git commit API 证明二者同 tree `71972650824144dcb3c59952e541ff2ea26f6b9c`。本记录只属于该固定源码，不替代 PR100 candidate `42515687138` 的独立失败，也不替代 PR102 或更新源码的结果。

| 作业 | 实际结果及本次报告复验 |
| --- | --- |
| development `128838073964` | SUCCESS；API28 常规原生输入两次清资料轮次、X-ASR JNI 与实际系统语音 UI |
| API35 native `128841047386` | SUCCESS；API35 同范围两轮及独立语音门禁 |
| API36 native `128841047400` | SUCCESS；API36 同范围两轮及独立语音门禁 |
| VoiceKey28 `128841047355` | SUCCESS；14 模型方法 + 23 确认方法 + 5 边界，watchdog 已计入14项 |
| VoiceKey35 `128841047438` | SUCCESS；同范围严格复验 |
| VoiceKey36 `128841047378` | SUCCESS；同范围严格复验 |

`github-metadata.json` 是 GitHub API 的归一化 JSON，含两提交、run、六作业和七个 artifact 元数据，不宣称原始 HTTP 字节。`producer-log-readback.json` 保留六份 connector 解码日志的 UTF-8 长度/SHA256及带行号 checkout 摘录；未公开完整日志或临时下载重定向 URL。`producer-sources.json` 固定实际验收脚本、工作流、模型锁和输入测试夹具 SHA256。

六个报告 ZIP 均已实际下载，大小及完整 SHA256 与 artifact API digest 一致，见 `artifact-manifest.json`。三个 VoiceKey ZIP 的23个成员均为安全报告/截图，原 ZIP 逐字节保留。development ZIP 含双 ABI APK、X-ASR 模型包和合成 PCM；API35/36 acceptance ZIP 各含合成 PCM。因此这三个原 ZIP 仅在临时工作目录下载核验，Git 中保留明确命名的 `reports-only.zip` 派生包，成员字节未修改，按路径排序及固定 ZIP 时间戳重打包；原完整 ZIP digest、每个原成员的长度/SHA256与保留/排除标记全部记录，派生包另有独立 digest，绝不称原 ZIP。没有模型、APK、PCM、数据库、密钥或恢复码进入 Git。test-harness artifact 只留 API 元数据，没有宣称独立下载验证。

在精确 head 的独立 worktree 执行 `python3 docs/evidence/pr100-full-android-42515688482/recheck.py` 通过。它只读报告、不调用 ADB/设备/网络或模型：直接执行同源 `android_voice_smoke.py` 的纯报告验证块，并调用同源 VoiceKey/同步准备报告器；先核 producer source SHA，再验证报告。三个 API 各两轮常规输入均有实际 instrumentation PASS、系统键盘包身份与服务退出原件；致命异常和 ANR 按原门禁判定，API28 runtime 日志中的系统 buffer 标题原样保留。三个 API 的语音每端另验证空PCM6、确认23、连接7、资料库5、同步准备11、备份8、VoiceKey边界5；实际 JNI 合成输入均125490样本。离线 UID 双栈规则移除、VoiceKey SAF 文档删除与软件键盘策略恢复分别复验，没有拿清理通过代替 UI 通过。

该源码的新同步 inventory preview 仍只有 Rust 共享层；本完整 Android workflow 没有生成其独立报告，也没有 JNI/UI preview 验收。这里的同步准备/本机备份报告不能替代首次恢复资料发布、完整同步、配对、冲突、历史密钥或真实HTTPS验收。没有本机重新推理或重跑 CI，未独立重放 WAV/PCM；VoiceKey 的波形 fixture 校验只由同源 CI producer 执行，其报告指纹已复验。以上均为 x86_64 临时模拟器及公开合成输入，不是 ARM/S25、自然人声、生产设备、正式签名或全部历史间歇组字问题已解决的证据。原失败继续保留。
