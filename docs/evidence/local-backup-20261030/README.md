# 本机加密备份 · 实际验收与失败保留

最终代码 `5e0258b19eb74b93be6c6381fbb98ac7c078125a` 经 [PR #69](https://github.com/ForceMind/ZapIME/pull/69) 合入 `07170f30859bec422cbb37d8c9f42069810d8805`。CI临时合并 `d63e767c37432eb62f0e38a46dbe2fadc6856e9c`、最终代码与合并结果的 tree 均为 `7024ea48ed499a5cb807cd44f30b62d65fdf0be2`。跟踪 [#68](https://github.com/ForceMind/ZapIME/issues/68) 仍保留正向SAF导入界面/手机恢复验收；#8的云账号配对与四端同步不在本机记录备份的完成范围内。

## 真正执行的检查

[Android 40934638777](https://github.com/ForceMind/ZapIME/actions/runs/40934638777) 双架构APK构建及三个系统作业均success。每API八项备份检查实际通过：原生快照、认证与空库恢复、非空/绑定保留、线程/边界、敏感Activity、实际系统ACTION_CREATE_DOCUMENT导出并恢复其实际字节、显式敏感复制与清除/新内容保护、退出清除；cleanup_completed=true。原native session在stateless恢复后显式重载，实际看见有效记录。

| API | 作业 | 备份 | 原连接/绑定保护 | 原空PCM/语音事务 | UID两族拒绝及清理 |
| --- | --- | --- | --- | --- | --- |
| 28 | 123661816993 | 8/8 | 7/7、5/5 | 6/6、20/20 | 10067，通过 |
| 35 | 123667008516 | 8/8 | 7/7、5/5 | 6/6、20/20 | 10209，通过 |
| 36 | 123667008389 | 8/8 | 7/7、5/5 | 6/6、20/20 | 10216，通过 |

三个combined-gates双脚本退出码均0；两轮真实系统输入、固定JNI中文片段、录音/权限/取消/焦点、空结果禁止插入及真正切回已核实系统IME并等待服务退出的门禁都保留。API28切回AOSP，现代系统切回pm list packages -s核实的Google预装语音IME；不是任意第三方或只检查输入PASS。合成语音和方法探针不等于自然语音、ARM手机或物理非空语音确认。

[通用40934638689](https://github.com/ForceMind/ZapIME/actions/runs/40934638689) 三系统Rust各87项/fmt/严格Clippy、macOS InputMethodKit与iOS主App/扩展构建及iOS3项通过。[服务40934638730](https://github.com/ForceMind/ZapIME/actions/runs/40934638730) 强制真实PG/S3、身份/撤销、缓存/对象归档、Docker、漏洞、一键安装重跑与新数据库/另一空桶AEAD恢复通过。[Windows40934638759](https://github.com/ForceMind/ZapIME/actions/runs/40934638759) 及[离线CPU40934638706](https://github.com/ForceMind/ZapIME/actions/runs/40934638706) success。workflows.json保留精确最终源及完整作业/步骤metadata；selected.log是原日志选段（Windows行尾转LF），不是完整日志。

本地Rust87项/fmt/严格Clippy、实际文件故障6项、Python17项和产品35源/61class、目标6源/15class的Java编译通过。local-summary/Java记录给出源码SHA、固定API36 jar及范围；这是本地检查，不借编译冒充Android执行。目标夹具只通过数字run/attempt及emulator专用脚本开启，测试只清理自己的合成资料与新建文档。

## 已下载文件

artifacts.json中的三个最终ZIP均已完整下载，字节数和GitHub SHA256一致：开发包233882262字节，API35为2443188字节，API36为2442414字节。raw-manifest.json逐项索引83个原始文本/JSON文件，按原字节保留在api-28/、api-35/、api-36/，不改写结果/末尾空行或系统日志尾空白；这些原件以SHA校验，文档/非原件另行检查空白。大体积截图、APK、模型、音频和ZIP留在Actions及ignored target，不进Git。

package-verification.json记录实际核验：ARM64 APK51144819字节/SHA `5a5bba9a4bfa01d9047bb91954b9033c6ded3896c9eab4877159c490c24a1d6a`；两APK的备份DEX类型、JNI动态导出、root GPL与crypto许可资产逐字节一致，原生依赖hash与固定模型六成员通过，测试探针/权重/PCM不混入产品。模型ZIP129097503字节/SHA `1e1ddde9def3868adac23842cdc5db42ad7d84e39452653a829d977f77a44fd1`。签名/manifest禁止明文与16KiB对齐由CI检查；未冒称本地重复签名或ARM执行。下载见[固定试用指南](../../ANDROID_TRYOUT.md)。

## 失败没有被覆盖

963421d的初次Android run40927920633/job123638295661因crypto crate缺独立LICENSE被原打包门禁拒绝。8272865原样复制root GPL，34931字节/SHA `5e376676c81ad41e9b690711db575bfd2623467b21814d7767373fda14aff80f`，未改检查白名单。该失败仅有636字节host报告ZIP，已完整核对SHA `78715a227884542da4a53368c7b9d9344a12e8095fc95bbd7577fef08f4d6867`，两份原报告保留于initial-host-only/；不是APK成功。

dd931eb的run40931084830/job123649557176在NDK官方下载解压报unknown archive，编译前失败且没有APK。保留精确失败选段；重跑后来被后续测试选择器提交取代，不称这轮重跑成功。

[first-attempt/](first-attempt/README.md) 完整保留a7f8da1的实际API28成功、API35/36失败及原始缺失报告事实。finally先finish再删除文档，临时URI权限失效且清理遮住报告，不能由首轮结果推断现代八项均执行。5e0258b只改测试收尾与报告，不新增产品存储权限、持久化授权或绕过SAF；产品/依赖/工作流与首轮逐字节相同，但最终三API另行真实重跑并全部通过。

## 可交付范围

本机五类记录与墓碑加密快照、写后读回、恢复码核对/显式复制，以及现有v2空白未绑定资料库事务恢复。独立格式见[说明](../../LOCAL_BACKUP_FORMAT.md)，与云同步材料隔离。记录归档4MiB/文件6MiB、系统提供程序并非原子/永久持久、清除平台副本及外部保存无保证、离开不能回滚已开始事务等边界见[使用文档](../../LOCAL_ENCRYPTED_BACKUP.md)。

真实SAF导出加独立空库JNI恢复，不是正向SAF导入按钮闭环或真实手机恢复；不含设备令牌、原同步根密钥/历史/失败页、模型或设置。没有Android正向HTTPS、用户服务器/真实Cloudflare、Apple/Windows系统激活、正式签名或四端互通完成的声明。此次后续文档归档只改文档与证据，不把其新head的独立CI运行冒称为已测试源码5e0258b的五绿。
