# Linux ORT 1.28.2 原归档恢复 · 2026-11-19

本切片接续 [PR #122](https://github.com/ForceMind/ZapIME/pull/122) 的头文件恢复，
基线为 `8865c969ef364a671e063e4a853d9e7328ed1815`。
日期采用 America/Los_Angeles，请求记录采用 UTC。
这里验证 Linux 主机诊断依赖的原字节恢复，不代表 Android、自然语音或真机验收。

## 实际恢复与来源

原 Microsoft 发布地址当前仍返回 HTTP404，既有官方来源和完整性期望没有改变：

`https://github.com/microsoft/onnxruntime/releases/download/v1.28.2/onnxruntime-linux-x64-1.28.2.tgz`

2026-11-19 13:19 UTC，从以下固定运输地址实际取得了归档：

`https://registry.npmmirror.com/-/binary/onnxruntime/v1.28.2/onnxruntime-linux-x64-1.28.2.tgz`

该请求经 HTTPS 重定向到 `cdn.npmmirror.com/binaries/onnxruntime/v1.28.2/`
下的同名文件。不能仅凭镜像文件名或其提供的摘要认定可信；本次下载在打开归档前
核对了修复前已经存在的整包长度/SHA，随后还分别核对原锁中的两个成员：

| 原件 | 字节数 | 原有 SHA-256（实取完全一致） |
| --- | ---: | --- |
| `onnxruntime-linux-x64-1.28.2.tgz` | 9,128,991 | `d7209b8751b27b862b0c76332c2e20e203396edb5dab700ecf4bb485cf147415` |
| `libonnxruntime.so.1.28.2` | 24,277,040 | `088f24b1fc56714d3efaaeb3ac2ee486a5d7b50ccfbb3dd26fd1a612534a05fb` |
| `LICENSE` | 1,073 | `2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c` |

[download-evidence.json](download-evidence.json) 保留初次实际下载的 URL、最终 URL、
时间、状态、长度及整包/成员摘要。其本机缓存路径是执行记录，不是需要用户具备的路径。
[此前调查](../ort-source-recovery-20261119/README.md) 的官方包索引和 SourceForge
失败仍原样保留；新的成功运输不能把旧404改写成成功。

## 代码只增加显式运输选择

`scripts/prepare_candidate_host_runtime.py` 的 `ARCHIVE` 官方 URL、版本、整包
长度/SHA及 `MEMBERS` 路径/长度/SHA保持不变。默认仍是官方来源。
新增 `--transport npmmirror-recovery` 只选择上述一个固定 HTTPS 运输地址；
没有任意 URL、环境变量覆盖、相邻版本选择或自动镜像回退。

所有运输复用原下载器的 TLS 校验、仅 HTTPS 重定向、大小上限、SHA 校验、
受限重试预算与临时文件发布。404、TLS或完整性失败保持失败，不尝试另一来源。
缓存命中也按相同原锁核验；`--offline` 不联网。归档解包继续只接受两个精确名称的
唯一普通成员，拒绝重复、链接、错误长度和错误摘要。

候选 CI 的 Linux 1.28.2 诊断步骤明确传入该选项，其他 Android 产品、模型、
运行库和质量断言均不变。归档和预编译库只存于构建缓存，不提交进源码仓库或源码 Alpha。

```sh
# 显式取得已经复核的同一原归档：
python3 scripts/prepare_candidate_host_runtime.py --transport npmmirror-recovery

# 已有精确缓存时，原默认/离线流程继续可用：
python3 scripts/prepare_candidate_host_runtime.py --offline
```

## 实际验证与范围

[local-validation.json](local-validation.json) 保存执行命令及完整输出：

- 主机归档回归8/8，包括原4项完整性/成员/链接保护，以及显式运输、404不切源、
  同长度坏摘要不覆盖旧库、未知运输在创建输出之前拒绝。
- 原下载/预算/TLS回归28/28。
- 原候选资源/头文件回归21/21。

[real-extraction-and-load.json](real-extraction-and-load.json) 对实际修改中的脚本、
测试和工作流记录 SHA，并保存两次真实命令结果：第一次把已恢复原归档放入临时缓存，
经既有离线提取器成功生成原 Linux 库与 MIT；第二次从空缓存通过新显式选项完成
真实 HTTPS 下载、整包及成员验证。两次均退出0。

Linux `dlopen` 和 `OrtGetApiBase/GetVersionString` 实际返回 `1.28.2`。
[linux-elf-static-inspection.json](linux-elf-static-inspection.json) 另记静态 ELF
依赖，包括 `libc.so.6` 与 `ld-linux-x86-64.so.2`；未使用 Android x86_64库替换它。

本次本机验证没有运行完整候选模型质量评测、GitHub Actions 或 Android/手机；
这些结果必须由对应后续提交的真实运行补充，不能由文件哈希或版本字符串推断。
文件摘要列于 [SHA256SUMS](SHA256SUMS)。
