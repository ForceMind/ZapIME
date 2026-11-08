# PR102 固定源码的 root 本机整合复核

远端 head `52f812bc269f35ba636997b9c4664c2b83a22154` 与本机 `fe4b9bef51ade9539d814ca9860211d423e542b0` 的完整 tree 均为 `2c73b5abe92214ad97eb0104f11911fa6a9d3cb6`，Git API 与本机只读核对一致；捕获时本机工作树干净。这是本机检查，**不是 PR102 原生/系统IME、远程Binder、ARM或手机 CI 通过**。

root 实际运行的130项Android Python、10项pending实际方法探针、根Rust143项/27结果组/0失败/0ignored通过；fmt通过与严格Clippy命令由root执行交接报告确认，Clippy原输出有正常Finished标记。fmt没有单独输出日志，不能伪造一份。测试组可能包含，不相加为新的独立样本。

第一次Rust尝试因新worktree没有初始化固定submodule而在执行测试前失败，原日志保留为NOT_RUN_FAILURE。随后root执行固定来源初始化/verify_upstream，MSIME为97d652a49cabb0887355b84186af2aa9032d3814、VoiceKey为3ba017826eef7fac405198510b03859eb5b6c8c4，再运行同源码143项通过；没有用“修产品代码”解释这次环境准备问题。

五份原输出逐字节保真gzip（mtime0），压缩/解压长度SHA、实际重算数量、来源和范围见validation.json；没有重跑native/推理/ADB/数据库，没有操作用户设备、生产或公开Release。原实验失败仍在独立目录，后续修复必须以PR102精确源码真实门禁验收。
