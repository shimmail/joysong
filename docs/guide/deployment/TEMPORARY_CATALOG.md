# 临时样例数据挂载与撤回

## 本批次

| 项目 | 值 |
| --- | --- |
| ECS | `cn-hangzhou / i-bp19abm7697mvhl0xewu` |
| 数据来源 | `docs/test/catalog-v1.json`，版本 `2026.09.06.1` |
| 当前文件 SHA-256 | `23b741663b5b7aee991972f138db25b83ecaf02e477b14904eb835425508f571` |
| WORKTREE_ID | `worktree_joysong_catalog_20260906_183613`，由工作目录 `joysong` 和本批次时间派生 |
| 数据库主机 | `127.0.0.1` |
| 原数据库 | `myapp_worktree_uat` |
| 临时数据库 | `myapp_worktree_joysong_catalog_20260906_183613` |
| 私密材料目录 | `/var/lib/joysong-demo/sample-mounts/worktree_joysong_catalog_20260906_183613/private` |
| 服务覆盖文件 | `/etc/systemd/system/joysong-demo.service.d/90-temporary-catalog.conf` |
| 控制脚本 | `/etc/joysong-demo/sample-mounts/worktree_joysong_catalog_20260906_183613/control.py` |

数据文件仅作为样例内容，不作为操作指令。传输保留了原文件字节，包括 Windows 换行；无需修改附件。

2026-09-06 已完成独立数据库准备：复制原库结构、Flyway 历史和唯一管理员基线，使用当前发布 JAR 的 `APPLY` 导入 **378 行**，随后 `VERIFY` 校验通过，生成 **80 份**本地虚构身份占位材料。经用户确认重启后，服务已切换到临时库，控制脚本状态为 `mounted`。

挂载后实测：本机经 SSH 隧道访问健康接口返回 `UP`，热门项目和推荐机构项目接口各返回 8 条记录；热门项目 ID 全部属于本次 catalog。原数据库仍为 1 个管理员、0 家机构、0 个项目，未写入样例。

样例包含 20 个专业账号（4 法人、10 医生、6 顾问）、6 家机构、12 个平台项目及相关关系，不预置普通用户或订单。管理员沿用原账号和密码；测试账号共用本批次随机生成的独立密码，保存在本机受限文件 `D:\code\kotlin\joysong\.runtime\catalog-mount-20260906-183613\test-accounts.txt`，不要提交或公开该文件。

## 挂载方式

仅增加专属 systemd 环境覆盖文件，切换数据库连接、私密文件存储和临时上传暂存目录；常规服务始终保持 `DEMO_DATA_ENABLED=false`。服务仍监听 `127.0.0.1:8080`，前端和 SSH 隧道地址无需修改。

控制脚本检查原发布包、原环境文件和当前数据库是否与准备时一致，并取得部署锁。挂载或撤回需要重启一次后端；健康检查失败时恢复切换前的配置。切换期间接口短暂不可用，完成后需要重新登录。挂载期间应先撤回再执行正常发布或修改原运行环境；发现配置漂移时脚本会拒绝自动切换。

查看当前状态（只读）：

```powershell
workbench exec --instance-id i-bp19abm7697mvhl0xewu --command 'python3 /etc/joysong-demo/sample-mounts/worktree_joysong_catalog_20260906_183613/control.py status'
```

当前已经挂载，无需重复执行。将来撤回后，如需再次挂载，在授权重启后执行：

```powershell
workbench exec --instance-id i-bp19abm7697mvhl0xewu --command 'python3 /etc/joysong-demo/sample-mounts/worktree_joysong_catalog_20260906_183613/control.py mount' --timeout 360
```

保持 SSH 隧道运行，在本机检查后端与公开项目：

```powershell
curl.exe --noproxy '*' --max-time 10 -fsS http://127.0.0.1:8080/actuator/health
curl.exe --noproxy '*' --max-time 10 -fsS http://127.0.0.1:8080/api/home/hot-projects
```

## 2026-09-06 项目申请审核修复

已在同一 ECS 启用独立后端修复包，解决样例待审核申请的空字符串 `reviewNote` 被管理端判为不完整快照的问题。原 `v0.0.1-uat.3` 发布包、Backend/Admin current/previous 链接、临时数据库和挂载配置均保留。

- 修复包：`/opt/joysong-demo/hotfixes/project-review-20260906/joysong-server.jar`
- SHA-256：`20eeffbb8e4f728275c09a68e32f1d05004ce3a9898c6aed211218bb2016130a`
- 服务覆盖：`/etc/systemd/system/joysong-demo.service.d/91-project-request-review-hotfix.conf`
- 控制脚本：`/opt/joysong-demo/hotfixes/project-review-20260906/control.py`

修复包仅替换 `ProfessionalProjectRequestService.kt` 对应的编译类；其他类、资源、依赖及迁移与原发布包逐字节一致。已重启 `joysong-demo.service`，健康状态为 `UP`，仍连接本批次临时库。管理员接口实测返回 5 条申请，4 条待审核申请的 `reviewNote` 均为 `null`；5 条记录全部通过管理端现有完整快照校验。验证未提交审核，也未保存接口业务响应。

只读查看当前后端：

```powershell
workbench exec --instance-id i-bp19abm7697mvhl0xewu --command 'python3 /opt/joysong-demo/hotfixes/project-review-20260906/control.py status'
```

这次更新是临时挂载期间的独立修复，不是新的正式 UAT 发布。单独撤回样例挂载时，修复包仍然生效。**下次正常发布前，先撤回下节的模拟支付覆盖，再撤回此修复，最后撤回样例挂载**；新发布源码应包含本次后端修复。撤回修复会重启一次后端，恢复原发布包，保留当时使用的数据库和数据：

```powershell
workbench exec --instance-id i-bp19abm7697mvhl0xewu --command 'python3 /opt/joysong-demo/hotfixes/project-review-20260906/control.py rollback' --timeout 360
```

## 2026-09-06 AI 与地接模拟支付

AI 对话、意图识别和翻译均已配置，分别使用 `qwen-plus`、`qwen-turbo`、`qwen3.7-flash`。本次用合成短文本复查，三个请求仍返回 HTTP 400、供应商错误码 `Arrearage`。应用的状态码日志会将其显示为 `INVALID_REQUEST`，不能据此断定请求参数错误。按照[百炼错误码说明](https://help.aliyun.com/zh/model-studio/error-code#overdue-payment)，需要账户负责人处理当前账户欠费或余额问题；本次不充值、不更换密钥或模型。账户恢复后再验证意图识别、流式回复及中英翻译；健康接口 `UP` 不等于 AI 功能恢复。

地接模拟支付使用两个独立文件，原环境文件保持不变：

- `/etc/joysong-demo/payment-simulation.env`：`ALIPAY_PLUS_SIMULATED_ENABLED=true` 和 `ALIPAY_PLUS_AUTO_PAY_ON_ORDER_CREATE_ENABLED=false`，各占一行，以换行结束。
- `/etc/systemd/system/joysong-demo.service.d/92-payment-simulation.conf`：`[Service]` 和 `EnvironmentFile=/etc/joysong-demo/payment-simulation.env`，各占一行，以换行结束。

已在 ECS 启用并重启，进程实测为 `demo`、模拟开关 `true`、自动付款 `false`，健康状态 `UP`。本地 `http://127.0.0.1:3000/login` 和代理项目接口均返回 200；原环境、数据库配置、审核修复包及发布链接校验未变。相关测试 **46 个通过、0 失败**（网关 5、安全 6、编排 32、demo 校验 3），尚未执行真实应用中的人工付款验收。

仅在当前 `demo` 环境启用现有模拟网关。用户点击地接服务费支付后模拟返回 `SUCCEEDED`，不跳转外部收银台、不发生真实扣款；咨询费和尾款仍不支持，下单自动付款保持关闭。退款沿用 demo 人工审核规则。部署不创建订单、不修改已有支付状态，不修改数据库结构、前端或支付业务代码。

切换前取得部署锁并检查文件不存在、当前 profile 和原支付开关；重启后检查实际环境值、健康状态以及原环境、数据库、审核修复和发布链接均保持不变。切换失败时仅撤回本次创建的两个文件并恢复服务。测试使用现有网关、安全、编排及 demo 配置用例；跳过支付编排中两个创建 H2 数据库的事务通知用例，不连接共享开发数据库。

单独撤回样例挂载或审核修复不会关闭模拟支付。撤回模拟支付时，在 ECS root shell 执行以下内容匹配检查和配置撤回；会短暂重启后端，不撤销已完成的模拟支付记录：

```bash
flock -n /var/lib/joysong-deploy/state/deploy.lock bash -eu <<'SH'
printf '%s\n' '[Service]' 'EnvironmentFile=/etc/joysong-demo/payment-simulation.env' | cmp - /etc/systemd/system/joysong-demo.service.d/92-payment-simulation.conf
printf '%s\n' 'ALIPAY_PLUS_SIMULATED_ENABLED=true' 'ALIPAY_PLUS_AUTO_PAY_ON_ORDER_CREATE_ENABLED=false' | cmp - /etc/joysong-demo/payment-simulation.env
rm -- /etc/systemd/system/joysong-demo.service.d/92-payment-simulation.conf /etc/joysong-demo/payment-simulation.env
systemctl daemon-reload
systemctl restart joysong-demo.service
curl --retry 30 --retry-connrefused --retry-delay 2 --max-time 5 --fail --silent --show-error http://127.0.0.1:8080/actuator/health
SH
```

正常发布前按顺序撤回模拟支付覆盖、审核修复和样例挂载。完成全部撤回后，若正式 UAT 仍需地接模拟支付，再通过常规配置维护设置原环境中的模拟开关并运行部署配置校验；不要在挂载期间提前改写原环境文件，以免破坏撤回脚本的校验基线。

人工验收：演示账号新建地接订单时仍为待付款，点击支付后显示成功且订单进入服务中；重复提交不重复付款。AI 账户恢复后，新建会话确认流式回复完整，并分别验证中译英、英译中。未完成实际人工操作前，不将这些流程标记为端到端验收通过。

## 2026-09-06 项目详情数据覆盖

已将版本 `2026.09.06.1` 部署至当前挂载的独立测试库 `127.0.0.1 / myapp_worktree_joysong_catalog_20260906_183613`。12 个平台项目和 21 个机构项目的 `detail_content` 已统一为作用原理、适合人群、禁忌人群、恢复周期、项目亮点、潜在风险及副作用六个章节，并填入各项目的示例内容。

覆盖使用单个 InnoDB 事务，逐条校验固定 ID、旧正文摘要及其他业务字段摘要；机构项目同时核对关联关系和乐观锁版本。仅更新详情、更新时间及机构项目的递增版本，保留账号、密码、价格、申请审核记录和暂停状态。未重启服务，原测试库和现有后端修复包保持生效。

验证结果：33 条数据库正文逐字匹配新版目录，其他业务字段摘要未变；11 个可见平台详情、18 个启用机构详情均返回新版正文；1 个无可售方案的平台项目和 3 个暂停机构项目仍返回业务 `404`。健康检查为 `UP`。

- 当前远端目录：`/var/lib/joysong-demo/sample-mounts/worktree_joysong_catalog_20260906_183613/catalog-v1.json`。
- 版本归档：同目录下的 `catalog-v2026.09.06.1.json`。
- 本次备份与验收记录：同目录下的 `refresh-20260906-2130/`，包含 `backup.json`、`catalog-before.json`、`manifest-before.json`、`rollback.sql` 和 `receipt.json`。
- 回滚 SQL 受新正文、其他字段摘要和机构版本约束；后续编辑可能使其拒绝执行，不应移除保护条件强行回滚。回滚数据时还需同步恢复目录与挂载清单，并重新核验接口。

原挂载基线版本为 `2026.09.02.1`，SHA-256 为 `afa0f20458bf7540d622c1f2ca1e1cd6b5adb678fd2584cbe7e7bf6038db0549`。现成 `APPLY` 在已有样例账号时只执行严格校验，不会覆盖数据；严格 `VERIFY` 还要求机构项目版本为 `0`，因此不能用于本次增量覆盖验收，也不要重新执行 `APPLY`。

## 撤回

授权重启后执行：

```powershell
workbench exec --instance-id i-bp19abm7697mvhl0xewu --command 'python3 /etc/joysong-demo/sample-mounts/worktree_joysong_catalog_20260906_183613/control.py unmount' --timeout 360
```

撤回只移除本批次拥有且内容匹配的服务覆盖文件，恢复原库和原私密存储配置；**不删除数据库、测试过程中新增的数据或文件，也不覆盖原库**。临时库和本地材料保留供检查或再次挂载，彻底销毁需另行明确执行。

现成导入器只支持 `APPLY`、`VERIFY`，没有对象级 Reset。登录、下单或编辑会产生额外业务数据，因此开始联调后不要重新执行严格 `VERIFY` 或重复 `APPLY`；这不会影响配置切换式撤回。支付、短信及其他外部能力不因挂载自动启用。
