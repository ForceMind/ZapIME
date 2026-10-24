# 预处理与beam对照原始证据

全部为固定合成中文，没有用户聊天或剪贴板文字。模型未接入输入法。

- `baseline-full-reproduction/`：从空target重新运行b85e328仓库原`reproduce.sh`产生的6份文本输出。四wheel、288,654,138字节原包、INT8转换及三句/十句网络可用与受限推理均实际成功。源文件SHA与命令边界在validation中，终端完整转录未另存。
- `contrast-*.json`：相同19句、SentencePiece-only/实际PRE=simple、beam 1/6、网络可用/受限，最终源码所选一次正式对照的8个新进程未筛选输出。包含规范化输入、SPM译文和原sed后处理原样结果、模型SHA、线程、系统、版本、负载时间与进程RSS范围。
- `preprocessing-comparison.json`：8个实际子进程退出码及整进程耗时，四组在线/离线结果和模型身份比较；原10句基线与前轮证据相同。没有质量评分。早期探索运行不计入这里的8进程指标。
- `preprocessing-manifest.json`：固定Moses来源提交、两helper和COPYING的SHA/大小/许可。原文件留在target，不进入Git或APK。
- `validation.json`：执行边界、原基线脚本与新探针脚本精确SHA、7项本地回归；不声明手机/原生Marian或教学质量通过。

原模型包与license SHA沿用[前轮证据](../english-learning-cpu-20261024/README.md)，本轮又实际下载核验。原来的坏例没有覆盖。受限方法仍只是Linux新进程的特定socket系统调用过滤，并非通用网络沙箱；暖文件缓存、单次计时和RSS范围不得外推移动端。

分析与重跑入口见[预处理报告](../../ENGLISH_TRANSLATION_PREPROCESSING.md)。否定含义倒转仍存在；当前配置不作为默认英语伴学模型。
