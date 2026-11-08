# 固定依赖原来源的当前获取证据

本次只读调查没有改下载代码、来源锁、URL、TLS、长度、SHA或镜像。云执行器对原固定官方HTTPS链接重新获取的结果已恢复成功，提供了用新固定源码进行CI验收的具体依据；这不是旧504失败已经消失的历史改写，也不是CI或系统IME通过。

| 固定资源 | 实际完整字节 | 原锁SHA256 | 当前结果 |
| --- | ---: | --- | --- |
| rerank-v2 8M ORT | 8,220,336 | e036febfb8f7bdf941f1637be127ff539ea95b937a4c2a27f4c6ab897138fc6c | HTTP200，完全匹配 |
| model.json | 951 | 33e2b903a0d576730488864a007e67d518d8c7123572e6f4f77a80487fb5eda5 | HTTP200，完全匹配 |
| vocab.json | 85,613 | d7fb2e70de9f0c2c65b822a8fbf604655bfef6d0dcd8f1975d73f1cd5ea7f038 | HTTP200，完全匹配 |
| 拼音词库 | 73,723,904 | 7c672458e6068c22c07b33b146c5a07f4e734959abc3bddd2784fe18a7582a76 | HTTP200，完全匹配 |
| 英语词库 | 4,526,080 | d216f66cab7edb9072ba7a07f3fe35623e76870438b76d360a89cc2a4795d3cd | HTTP200，完全匹配 |
| 词库manifest | 4,627 | 5b656a5db35b4dcf804ce4348d8584c899d0012fe62bbbe3689204ddaba8722f | HTTP200，完全匹配 |
| X-ASR归档 | 133,895,136 | fa5f63d618e5a01526e275a358bb7772e403f84808a4769fba52cffd8160bf74 | HTTP200，完全匹配 |

`access-results.json`记录四次原URL获取（自定义审计User-Agent）；`large-pinned-results.json`记录三个大资源用urllib默认请求头的完整流式SHA。为排除仅审计请求可用，`exact-production-results.json`另在空目录直接调用现有`prepare_candidate_model.download`，没有改OPENER/默认请求头/重试策略，两个模型资源均完整核验成功。读取均从网络获取，未复用本地缓存文件；不声明绕过CDN或代理缓存。大词库/语音只流式计算SHA，未把载荷写入此目录或上传；模型下载载荷仅在scratch，没有进入Git。

`official-model-release.json`为官方release API筛选元数据：release433387133、固定rerank-v2的三个asset ID/size/digest均与锁一致；并不意味着需要切换到API地址或新增回退路径。原官方下载URL已经可用，无需改镜像、放宽校验或重新做模型整合。

`context.json`给出记录时间、被调用脚本与main d38e6ce的Git blob/字节身份。耗时仅是本轮下载观测，不是设备/模型推理性能。重定向只记录主机名，没有签名下载URL、凭据或载荷。各HTTP请求没有抓包，不能把顶层调用计成wire请求数。

本证据仅证明当前云执行器的这些获取成功；不能据此认定CI网络已经恢复，不能统一推定旧504由哪一端或哪个具体成员引起。下一步必须保存原失败，在包含当前产品修复的新CI源码上重新执行构建、系统IME、四项方法与配置实验。没有在本次审计运行推理、JNI、模拟器、手机或发布。
