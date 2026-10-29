# 首轮结果保留

本目录对应代码 `a7f8da1f3dbacde47a07b058c153c730c1fc4123`，CI 合并树为 `e28a8775993ef8ed1d23a39bd1982654cfc36af0` / `2eb0c9ac0a01541f02c167098e46b403b5dc54e7`，Android run `40931766007`。

API28 的实际备份八项及原输入/语音/退出门禁成功。API35 与 API36 则真实失败，两个汇总均为输入脚本 0、语音脚本 1；原始 instrumentation 保留提供程序权限异常。本轮没有拿到现代系统的 local-backup-report：探针 finally 在 Activity 结束后删除新建文档，临时 URI 权限已撤销；该清理异常还阻止报告写入。因此无法从首轮结果认定现代系统八项已经全部执行，不补造缺失报告。

随后 `5e0258b` 把测试文档删除放到 Activity 结束前，逐项收尾并无论清理结果均写报告，保留原始操作失败；采集器还要求 cleanup_completed=true。没有新增产品存储权限、持久化许可或跳过系统 SAF。产品源码、依赖及工作流相对首轮逐字节不变；新执行另在上一级按最终源码记录。

三个首轮 ZIP 已完整下载并核对大小及 GitHub SHA256，见 artifacts.json。api-28/ 保存成功原件；api-35/ 与 api-36/ 保存实际失败及独立输入退出原件。package-verification.json 只对应首轮产品包，不能替代后续重建的 APK 校验值。大体积 ZIP/APK/模型/PCM 不进入 Git。

首轮通用三系统 Rust 各87项及 Apple 编译/iOS3项、服务强制PG/S3/一键安装恢复、Windows与离线CPU通过，相关日志只是逐行选段（Windows换行为LF），不是完整作业日志，也不是最终修复源码的新执行结果。
