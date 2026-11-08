# 固定资源下载受限重试：PR #98 实际首轮证据

PR [#98](https://github.com/ForceMind/ZapIME/pull/98) head 为 `fb62ad923f376edb7f1c3ab274fc8095718b7ea7`，实际 PR checkout `0647d0bc6734e915821ccc5ddc9bf8f87bf3c299`。两者 tree 都是 `f99bbff5cc9ca044e5e9fe190f0f58c243434930`。全部为 attempt 1，没有手动重跑。原有 Alpha 标签与公开附件未变更。

| 实际作业 | 结果与首个失败 | 已执行及未执行范围 |
| --- | --- | --- |
| Candidate host `128687392403` / run `42466578708` | 固定候选资源最终 HTTP504 | 新 28 项合成回归通过；真实模型数值、JNI 与质量推理未执行 |
| Candidate build `128687392256` / 同 run | 固定候选资源最终 HTTP504 | 既有资源 14 项与 catalog 通过；APK 和三 API candidate matrix 未执行 |
| Android build `128687392745` / run `42466578712` | 固定候选资源最终 HTTP504 | 新 28 项及既有资源 14 项通过；双 ABI APK、语音 ZIP、API28 系统门禁未执行 |
| Voice CPU `128687392394` / run `42466578721` | 固定语音归档最终 HTTP504 | 新 28 项、语音质量工具 16 项通过；合成 WAV/真实离线识别未执行 |
| Apple `128687393170` / run `42466578822` | `build_macos.sh` 的固定词库下载最终 HTTP504 | 日志步骤名虽为 IMK 编译，实际在 fetch_resources 阶段即失败；Rust release/clang IMK、iOS/XCTest 未执行 |
| Source prepare `128687392092` / run `42466578724` | SUCCESS | 发布工具 30 项、根 Rust 134 项、fmt/Clippy 与精确提交源码打包通过；PR 的发布步骤按条件 SKIPPED，不是新公开发布 |
| Shared Windows/macOS/Ubuntu `128687393145` / `128687393218` / `128687393261` | 各 SUCCESS | 根 Rust 分别 131/134/134，failed/ignored/measured/filtered 均为 0；Windows 的 3 项 Unix 条件测试未编译，详见原摘要 |

五个 Android 后续 job `128687821018`、`128687821072`、`128687821086`、`128687821099`、`128687821106` 均在下载 `ZapIME-Android-development` 时失败，因为上游尚未生成 APK 附件；它们没有执行系统 IME 或语音验收。三条下载相关 workflow 附件列表均为空。Shared workflow 因 Apple 下载失败整体 FAILURE；不能用其中三个 shared job 的成功描述整个 workflow 通过。

五个实际下载失败都保留两条 `attempt=1 next=2 wait_seconds=1`、`attempt=2 next=3 wait_seconds=2` 日志与最终原 HTTPError504。结合已核固定源码，这是**包含首次在内最多 3 次顶层尝试**，不是三次额外重试；日志不是 wire 抓包，不能据此断言重定向/代理层实际 HTTP 请求总数。未由日志证明具体模型/头文件/词库成员、哪一端产生 504 或唯一网络根因。三处 28 项成功均为自写合成网络替身测试，其中的 PASS 和重试文本不是实际资源获取成功。

本机/CI 合成测试证明了完整性错误与文件系统错误不重试、每轮 partial 清理与旧文件保护；真实失败日志没有逐项列出 runner 临时目录，不能宣称已观察到真实目录清理。保持固定 URL、SHA/长度、默认 TLS 与许可边界，无镜像或校验回退。180/360 秒为读取/发布边界检查的单调调度预算，不能硬杀已阻塞的 DNS/系统/socket 调用。

`download/` 保留四个下载失败及五个缺产物作业的完整安全日志（gzip，mtime=0）、原摘要、source/job metadata、四份远端源码 blob 与已审本地代码的字节相同证明；`source-shared/` 保留另五个完整日志与独立审查原摘要。14 份 gzip 解压后与取回原字节 SHA/长度一致，Windows BOM/CRLF 保留。没有 APK、模型、录音、输入资料或凭据。原 `download/verify_failure.py` 会重写派生摘要和 gzip，只作为生成过程原件归档，复核请运行根目录的只读验证器：

```sh
python3 docs/evidence/build-download-retry-ci-20261108/verify.py
python3 -m unittest discover -s docs/evidence/build-download-retry-ci-20261108 -p 'test_verify.py' -v
```

只读验证器严格检查 JSON 字段、布尔与数字类型、固定身份、逐项日志 SHA/长度、真实与合成重试分界、最终失败和 NOT_RUN，并独立重算 Rust 测试计数。变异回归刷新外层 manifest 后仍须拒绝真值数字伪装、错误尝试数、篡改源/原件、重复字段、未知 payload、重复案例及把未执行编译改为成功。

审查曾发现只核日志、未对照摘要计数，以及仅核容器外层类型的缺口；本目录保留对应拒绝复现：将源码测试 passed 改为 999、Rust passed 改为 999/ignored 改为 99，或把候选测试计数数组全部改成浮点，即使同步刷新 manifest 仍必须失败。修正后摘要数值逐项与原日志派生值同型相等。

本目录只报告上述固定作业。其他 Windows 原生、独立服务及 VoiceKey focused workflow 不在此证据范围内，未借用它们的旧成功；全项目最新结论由 STATUS 的后续固定记录维护。
