# 固定9b801ff源码：focused VoiceKey、同步准备与HTTPS原件复验

本轮只对应 PR96 head `9b801ff947486f5418e9ddc84a88941d1cf3eda6` / tree `3995757340519d1b21fc5dfb3b2b89367673d087`，实际checkout `436b267cbbc5885ecdf58a7515ef0323fbb6f5e8` 的完整Git树相同，父为d38e6ce与9b801ff。工作流 `42513949694` attempt1最终 **8/8 SUCCESS**，没有重跑。此处不代表后续产品修订、其他候选/完整Android工作流或手机已通过。

| 实际门禁 | 本轮原报告独立复验 | 边界 |
| --- | --- | --- |
| VoiceKey API28/35/36 | 每端14个实际模型/SAF/生产者case、23个Editable确认case、5个边界case；watchdog原报告和模型case逐字段一致。原系统IME/模拟器AudioRecord生命周期通过标记、系统键盘恢复/真实服务退出、共享文档删除、双栈UID规则移除及空fatal均已核实。 | Watchdog属于模型case集合，不另加成独立新样本。权限变化case的OS撤权标记保持false；不把模拟权限检查称真实OS撤权。模拟器麦克风、合成录音/识别和本机事务不是自然语音质量或真实手机。 |
| 本机同步准备 API28/35/36 | 每端11 preparation +10 UI +14 key +12 transfer +10 pages，合计57项；逐份调用同源严格报告器，实际SAF创建/打开、恢复身份不变、缺材料恢复、单次文件触摸/导航遮挡、文档删除与secret UI清理、网络规则清理全部通过。 | 本机合成准备/JNI与受控页面门禁，不等于账户全局同步、双设备配对或用户备份保管。 |
| 实际HTTPS API28 | before2 + roundtrip10 + after2，共14个case，均由同源严格报告器复验；真实Go/PostgreSQL双向两条操作/两个隔离profile、错误SAN在HTTP之前拒绝、CA移除前后默认平台TLS拒绝、CA基线/UID规则/测试包清理通过；安装APK SHA与本轮build metadata一致。 | 单个受保护临时API28 AOSP模拟器加本轮临时系统CA，不是两个手机、默认根库可直接信任、公网PKI、R2、配对或生产部署。API35/36 HTTPS明确BLOCKED_NOT_RUN。 |

`archive-manifest.json`保存8份完整报告/小build ZIP的原artifact ID、大小和SHA，下载后均逐字节匹配GitHub摘要；`archive-members.json`逐成员列长度/SHA。所有原ZIP只含安全报告、日志及受控合成截图，不含APK、模型权重、PCM、私钥或录音。59MB inputs仅保留API元数据，**没有下载**或上传。原源码Alpha标签/资产没有修改。

VoiceKey原WAV没有下载或重构，报告复验不冒称独立PCM复播；从已核同源脚本与作业SUCCESS可确认CI执行了`verify_fixture_report`，本地仅重验模型/包/报告身份与fixture摘要的格式及各生产者计数边界。自然语音、ARM/手机仍为原NOT RUN。不同构建job可有不同开发签名；本轮HTTPS只对照本focused build元数据，没有把candidate另一job的APK摘要套入。

`strict-validation.json`为派生复验结果；`verification-source.json`保存实际导入严格报告器/锁文件的Git blob和SHA，与本轮固定源码匹配。复核只调用报告函数，没有执行ADB、网络、推理、系统CA操作或用户设备动作。原报告始终原字节保存，派生摘要不替换原件。

方法验收所调用的同源函数为：`android_voicekey_model_smoke.verify_reports/verify_boundary_report/verify_watchdog_report`；`android_sync_preparation_report.verify_report/verify_ui_report`；key/transfer/pages各自`verify_report`；`android_sync_https_report.verify_report`与`android_sync_https_smoke.verify_fixture_summary`。另核实际服务退出、fatal/规则清理、phase execution返回码以及compiled APK门禁与本轮生产者SHA。

此focused成功不能覆盖同源码候选工作流42513949719的API35 SDK ZIP准备失败，亦不能覆盖配置实验中`fresh_superseded`的真实保留失败。旧失败、新成功和未执行范围继续分开保留。
