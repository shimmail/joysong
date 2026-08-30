# 单一固定管理员完整链路手工验收

本文从全新隔离部署开始，验收唯一固定管理员 `A-01` 的创建、登录、无临时密码重启、管理职责、固定边界、改密、退出、并发和受控改号。

生产变量和管理员停机改号 SQL 以[项目配置指南](../guide/CONFIGURATION_GUIDE.md)为准；跨角色共享账号、业务对象和执行顺序以[全角色最小联调测试总流程](README.md)为准。本文不定义 `A-02`，也不包含新增管理员、普通用户升为管理员或管理员降权流程。

## 1. 验收目标与结论

必须证明：

- 迁移后的 `users=0` 时，`ADMIN_PHONE + ADMIN_PASSWORD` 只创建一个 `ACTIVE + ADMIN`；
- 创建后移除 `ADMIN_PASSWORD`，同库重启只验证现有管理员，不重置密码、资料或 `userId`；
- 管理登录只接受与 `ADMIN_PHONE` 完全一致的裸 11 位号码，并要求数据库账号为 `ACTIVE + ADMIN`；
- 管理端平台角色只读，不存在新增管理员或角色切换入口；
- 固定管理员不能被暂停、恢复、在线换号或注销；普通账号不能占用其裸号或 `+86` 等价号；
- 改密会使所有旧管理会话失效；服务端退出成功会使对应会话失效；
- 配置漂移、异常状态、等价号冲突或第二个非注销管理员都会失败关闭且不写数据；
- 同一 `A-01` 的两个独立会话可以完成并发与幂等验收，无需第二管理员。

结果只使用：

| 结论 | 含义 |
|---|---|
| 通过 | 实际结果和证据完整满足预期 |
| 失败 | 功能可执行，但结果不满足预期 |
| 阻断 | 环境、依赖或安全前置条件不满足，不能继续 |
| N/A | 当前产品明确不提供该能力 |

## 2. 账号模型

测试记录必须分开四个维度：

| 维度 | 取值示例 | 固定管理员规则 |
|---|---|---|
| 账号状态 | `ACTIVE` / `ADMIN_SUSPENDED` / `ERASED` | `A-01` 只能保持 `ACTIVE` |
| 平台角色 | `USER` / `ADMIN` | `A-01` 固定为 `ADMIN`，其他账号固定为 `USER` |
| 专业身份 | 法人 / 医生 / 顾问 | 与平台管理员角色独立 |
| 机构关系 | 法人任职、医生/顾问 `JOIN/LEAVE` | 按各业务分册流转，不由平台角色自动推导 |

`A-01` 不因平台管理员身份自动获得医生、顾问或机构法人身份。普通专业用户即使拥有有效专业身份，其平台角色仍为 `USER`。

## 3. 隔离环境与测试数据

### 3.1 数据库安全

1. 使用全新、可丢弃的隔离数据库，禁止连接共享开发库或生产库。
2. worktree 测试数据库使用 `myapp_<WORKTREE_ID>`，Docker Compose 项目名使用 `myapp-<WORKTREE_ID>`。
3. 启动或迁移前输出并保存数据库主机、数据库名和 active profiles。
4. 只有数据库名以 `myapp_worktree_` 开头时才允许执行 drop/reset；其他数据库只能新建或保留。
5. 管理员改号成功场景涉及直接数据库事务，只能在受控备份或获准的维护窗口执行。

### 3.2 固定数据

| 编号 | 值 | 用途 |
|---|---|---|
| `A-01` | `13800009001` | 初始唯一固定管理员裸号 |
| `A-01-E164` | `+8613800009001` | 普通 App 等价号拒绝测试 |
| `A-NEW` | `13800009002` | 可选的受控停机改号目标 |
| `A-NEW-E164` | `+8613800009002` | 新号等价冲突检查 |
| `${TEST_ADMIN_PASSWORD}` | 由秘密管理器提供 | 首次 Bootstrap 密码和首次登录密码 |
| `${TEST_ADMIN_NEW_PASSWORD}` | 由秘密管理器提供 | 改密后的新密码 |

密码正文、完整密码哈希、AccessKey 和完整 token 不得进入截图、日志或缺陷单。`${TEST_ADMIN_PASSWORD}` 由执行者安全保存，只在首次启动时临时映射给服务端 `ADMIN_PASSWORD`；移除服务端变量不等于忘记登录密码。

### 3.3 空白起点

开始前应满足：

- 目标数据库已预创建，尚无业务数据；
- `users` 表尚不存在，或迁移后总行数为 `0`；
- 没有任何 `A-01`、普通用户、身份申请、机构、项目或订单；
- 服务端、管理端、Flutter 和数据库版本已记录；
- 若使用生产 profile，所有硬性变量已按配置指南准备。

如果无法得到空白隔离起点，停止 Bootstrap 主链路；不得删除共享库数据凑状态。

## 4. 与总流程的唯一执行关系

| 类型 | 用例 | 与总流程的关系 |
|---|---|---|
| 默认主链路 | `ADM-P0-00`～`ADM-P0-03` | 详细展开总流程 `SMOKE-00` |
| 默认主链路 | `ADM-P0-04` | 汇总 `SMOKE-02`～`SMOKE-07` 中管理员承担的动作 |
| 管理员专项 | `ADM-ACCOUNT-P1-01` | 暂停/恢复非管理员账号 |
| 管理员专项 | `ADM-ACCOUNT-P1-02` | 固定管理员和保留手机号失败关闭 |
| 管理员专项 | `ADM-SEC-P1-01` | 改密、服务端退出和旧会话失效 |
| 管理员专项 | `ADM-ACCOUNT-P1-03` | 单一固定管理员专项终态 |

`README.md` 的 `SMOKE-00`～`SMOKE-08` 是唯一默认跨角色执行链。本文不建立第二套默认主链路；普通账号暂停/恢复、改密、退出、受控手机号迁移、启动失败矩阵和并发只在管理员生命周期专项或相关发布门禁中执行。

## 5. 总流程详解与管理员专项

### `ADM-P0-00` 空白部署与配置确认

1. 在任何迁移或启动前输出数据库主机、数据库名和 active profiles。
2. 确认数据库是本轮隔离目标，且没有既有业务数据。
3. 配置 `ADMIN_PHONE=13800009001`。
4. 临时配置 `ADMIN_PASSWORD=${TEST_ADMIN_PASSWORD}`；长度必须为 `12–128` 位，生产建议同时包含大小写字母、数字和特殊字符。
5. 确认普通 App 使用的等价号 `+8613800009001` 尚未被注册。

通过：环境目标可追溯，首次启动前不存在管理员或普通用户。

### `ADM-P0-01` 首次 Bootstrap 创建

1. 只启动一个后端实例，等待 Flyway 和 Bootstrap 完成。
2. 检查健康接口和启动日志，不记录密码。
3. 查询或通过受控管理证据确认 `users` 只新增一行。
4. 记录 `A-01` 的 `userId`、账号数量和密码哈希指纹。
5. 确认没有为 `A-01` 创建法人、医生或顾问专业身份。

预期：

| 字段 | 预期 |
|---|---|
| `phone` | `13800009001` |
| `role` | `ADMIN` |
| `account_state` | `ACTIVE` |
| `nickname` | `系统管理员` |
| `password_hash` | 非空，只保存不可逆指纹用于前后比较 |
| 账号总数 | `1` |

空 `users` 表且 `ADMIN_PASSWORD` 为空、少于 12 位或超过 128 位时必须拒绝启动，并且不创建半成品管理员。

### `ADM-P0-02` 首次管理登录与入口隔离

发送：

```http
POST /api/admin/login
Content-Type: application/json

{"phone":"13800009001","password":"<TEST_ADMIN_PASSWORD>"}
```

成功预期：HTTP `200`、响应体 `code=200`，返回的 `user.id` 等于 Bootstrap `userId`，`role=ADMIN`、`accountState=ACTIVE`，同时返回 access token 和 refresh token；响应含 `Cache-Control: no-store`。

登录失败矩阵：

| 输入 | 预期 |
|---|---|
| 正确格式但不存在的号码 | HTTP `401`，统一“管理员账号或密码错误” |
| `SMOKE-01` 注册完成后的现有普通用户号码 | HTTP `401`，同一消息；本行在 `SMOKE-01` 后补测 |
| `A-01` 配错误密码 | HTTP `401`，同一消息 |
| `+8613800009001` | Bean Validation HTTP `400` |
| 位数错误、含空格或字母 | Bean Validation HTTP `400` |
| 连续失败达到阈值 | HTTP `429`，含 `Retry-After` 和 `Cache-Control: no-store` |

`200/401/429` 由管理登录控制器设置 `no-store`；参数校验阶段产生的 `400` 当前不保证该响应头。

再使用 `A-01` 调普通 `POST /api/auth/login`。预期普通入口拒绝管理员；不得通过验证码登录、注册或 Google 登录绕过独立管理登录。此时数据库尚无普通用户，“普通用户不能管理登录”必须等 `SMOKE-01` 注册完成后使用真实 `ACTIVE + USER` 补测，不能复用“不存在号码”的证据。

### `ADM-P0-03` 移除临时密码后的二次启动

1. 保存首次启动后的 `userId`、手机号、角色、状态、昵称、账号数量和密码哈希指纹。
2. 受控停止所有后端实例。
3. 完全移除 `ADMIN_PASSWORD`，保持 `ADMIN_PHONE=13800009001`。
4. 使用同一数据库重新启动一个实例。
5. 使用首次密码再次登录管理端。
6. 对比重启前后快照。

通过：服务正常启动，原密码仍可登录；上述字段和账号数量全部不变，Bootstrap 没有执行密码编码或任何数据写入。

已有合规管理员时，即使部署环境误留任意 `ADMIN_PASSWORD`，该值也会被忽略。不得把重新设置环境变量当成重置管理员密码的方法。

### `ADM-P0-04` 全角色主流程中的管理员职责

按[总流程](README.md)复用同一组账号和对象，管理员只执行以下职责：

1. 批准 `L-01` 法人身份，确认机构、法人任职和机构钱包按业务规则初始化。
2. 批准 `D-01` 医生身份，确认医生档案和钱包初始化，但不自动加入机构。
3. 批准 `C-01` 顾问身份，确认顾问钱包初始化，不创建医生式专业档案、不自动加入机构。
4. 批准 `P-01` 平台项目。
5. 查看 `O-01` 全局订单视角，不修改支付事实。

管理员不替代机构法人审批医生/顾问的正常 `JOIN/LEAVE`，也不替代法人审批机构项目。三个专业账号的平台角色始终为 `USER`。

驳回、撤销专业身份、人工覆盖机构关系和异常项目审核属于第 9 节专项，不重复创建主流程对象。

### `ADM-ACCOUNT-P1-01` 暂停和恢复普通账号

选取一个非管理员 `ACTIVE + USER` 测试账号：

1. 用 `A-01` 调用 `PUT /api/admin/users/{userId}/deactivate`。
2. 确认账号变为 `ADMIN_SUSPENDED`，旧 access/refresh token 失效，密码和验证码登录被拒绝。
3. 确认用户的专业身份、档案、钱包、机构关系和历史业务数据没有被删除。
4. 调用 `PUT /api/admin/users/{userId}/reactivate`。
5. 确认账号恢复为 `ACTIVE`，必须重新登录后才能继续业务。

管理列表和详情应继续显示暂停账号以便追踪；`ERASED` 账号不可恢复。

### `ADM-ACCOUNT-P1-02` 固定管理员与保留手机号边界

| 操作 | 当前预期 |
|---|---|
| 管理端新增管理员 | 无页面、无受支持业务流程 |
| 用户列表/详情的平台角色 | 只读标签，不发送角色切换请求 |
| `PUT /api/admin/users/{id}/role` | HTTP `404` |
| 暂停 `A-01` | HTTP `400`、`code=400`，固定管理员不能被停用 |
| 恢复 `A-01` | HTTP `400`、`code=400`，固定管理员不能被恢复 |
| 固定管理员走 `PUT /api/user/phone-change` 在线换号 | HTTP `200`、body `code=400`，管理员手机号不变 |
| 固定管理员发起 `POST /api/user/account-deletion/preflight` | 非生产专项环境开启功能后 HTTP `200`、`eligible=false`、blocker 含 `ADMIN_ACCOUNT`；生产或默认关闭时 HTTP `503`、`ACCOUNT_DELETION_DISABLED` |
| 普通账号用裸号 `13800009001` 调 `POST /api/auth/register`、`POST /api/user/bind-phone`、`POST /api/user/phone-change/send-new-code` 或 `PUT /api/user/phone-change` | Bean Validation HTTP `400`、body `code=400`、提示手机号格式不正确；这只证明 App 端点要求 E.164，不作为保留号命中证据 |
| 普通账号注册 `+8613800009001` | 先为该号码取得有效验证码，再调用 `POST /api/auth/register`；HTTP `200`、body `code=400`、提示手机号已注册，不创建账号 |
| 未绑定手机号的 `ACTIVE + USER` 首次绑定 `+8613800009001` | 携带该账号 access token 调 `POST /api/user/bind-phone`，请求体使用格式正确的 6 位验证码；HTTP `200`、body `code=400`、提示该手机号为系统管理员保留号码，账号仍未绑定手机号 |
| 已绑定手机号的 `ACTIVE + USER` 请求把 `+8613800009001` 作为新号 | 调 `POST /api/user/phone-change/send-new-code`；HTTP `200`、body `code=400`、提示该手机号为系统管理员保留号码，不发送新号验证码 |
| 普通账号最终换绑为 `+8613800009001` | 调 `PUT /api/user/phone-change`，请求体使用格式正确的 6 位验证码；HTTP `200`、body `code=400`、提示该手机号为系统管理员保留号码，原手机号和会话不变 |

执行上表时，注册请求使用有效验证码和格式正确的 `8–128` 位普通账号密码；首次绑定、发送新号验证码和最终换绑请求均携带对应 `ACTIVE + USER` 的 access token。除注册必须先通过真实验证码校验外，其余保留号检查都发生在验证码消费或手机号写入之前。

固定管理员行不显示暂停或恢复按钮。以上失败不得改变 `A-01` 的 `userId`、手机号、密码哈希、角色、账号状态或会话。

注销 blocker 只能在单独的非生产隔离环境执行：设置 `ACCOUNT_DELETION_ENABLED=true`、`ACCOUNT_DELETION_ALLOW_COMMERCE_BYPASS=true`，再携带 `A-01` access token调用 preflight。生产 profile 会硬关闭该能力；非生产若未启用 commerce bypass，当前不可用的 commerce port 也可能先返回 `503`。禁止为了该用例修改生产配置。

### `ADM-SEC-P1-01` 改密、退出和旧会话失效

先用 `A-01` 登录两次，保存独立会话 `S1`、`S2` 的 access/refresh token。

使用 `S1` 改密：

```http
PUT /api/user/password
Authorization: Bearer <S1 access token>
Content-Type: application/json

{"oldPassword":"<当前密码>","newPassword":"<TEST_ADMIN_NEW_PASSWORD>"}
```

新密码必须为 `12–128` 位并包含大小写字母、数字和特殊字符。预期：

- 改密为 HTTP `200`、body `code=200`；
- 旧密码调用 `POST /api/admin/login` 为 HTTP `401`、body `code=401`；
- `S1`、`S2` 的旧 refresh token 调用 `POST /api/auth/refresh` 均为 HTTP `401`、body `code=401`；
- `S1`、`S2` 的旧 access token 访问 `/api/admin/users` 均为 HTTP `401`、body `code=401`；
- 新密码调用 `POST /api/admin/login` 为 HTTP `200`、body `code=200`；
- `userId`、手机号、角色和账号状态不变。

改密使该 `userId` 的全部旧会话失效。随后用新密码再登录两次，保存新会话 `S3`、`S4`；只对 `S3` 直接调用服务端退出：

```http
POST /api/auth/logout
Content-Type: application/json

{"refreshToken":"<S3 refresh token>"}
```

预期退出为 HTTP `200`、body `code=200`；随后 `S3` refresh 调用刷新、`S3` access 访问 `/api/admin/users` 均为 HTTP `401`、body `code=401`。先确认 `S4` access 仍能以 HTTP `200`、body `code=200` 访问 `/api/admin/users`，再确认 `S4` refresh 仍能以 HTTP `200`、body `code=200` 成功刷新，证明服务端退出只撤销传入 refresh token 对应的单个会话，而不是该管理员的全部会话。

管理端“退出”按钮会先清本地状态，再异步尽力发送撤销请求；页面立即跳转不能代替服务端撤销成功证据。若请求仍在途或失败，应单独记录短暂竞态或安全缺口。

### `ADM-ACCOUNT-P1-03` 管理员专项终态

管理员生命周期专项结束时必须同时满足：

- 数据库中只有一个非 `ERASED` 管理员；
- `A-01` 仍为同一 `userId + ACTIVE + ADMIN`；
- `ADMIN_PASSWORD` 不存在于部署环境；
- 当前密码可通过独立管理入口登录；
- 管理端没有新增管理员、角色切换或固定管理员暂停/恢复入口；
- 不存在 `A-02`、升权、降权或“最后管理员”测试。

## 6. 初始管理员手机号怎么修改

### 6.1 首次创建前

在管理员尚未创建且 `users=0` 时，修改 `ADMIN_PHONE` 后再首次启动即可。同步使用新裸号登录，临时 `ADMIN_PASSWORD` 的规则不变。

### 6.2 首次创建后

产品不支持在线改号。只改环境变量会触发配置漂移并拒绝启动；普通换号接口也会拒绝固定管理员。

成功改号只能按[配置指南第 3.2 节](../guide/CONFIGURATION_GUIDE.md#32-首次创建后修改)执行停机运维事务：

1. 停止所有后端实例并创建可恢复备份。
2. 核对当前唯一非 `ERASED` 管理员是目标 `userId`，状态为 `ACTIVE + ADMIN`，密码哈希非空。
3. 核对新裸号和其 `+86` 等价号无人占用。
4. 在一个事务内锁定管理员 guard 和相关用户行，更新同一用户行的手机号与 `credentials_updated_at`，撤销全部 refresh token；管理员更新影响行数必须恰好为 `1`。
5. 在服务仍停止时同步修改 `ADMIN_PHONE`，保持 `ADMIN_PASSWORD` 缺失。
6. 启动单实例，用新号和原密码登录；确认旧号失败、旧会话失效、`userId` 和密码哈希不变。
7. 回滚时必须同时恢复数据库手机号和部署配置，不能只恢复一侧；如果新号阶段已经启动或登录，还要再次更新凭据时间并撤销该 `userId` 的全部 refresh token。

这是运维迁移，不是动态管理员生命周期。当前没有自动化测试覆盖成功改号；未执行受控事务时只能验证“配置漂移失败关闭”，不能标记“改号通过”。

## 7. 启动失败关闭矩阵

每个负例使用独立的可恢复数据库副本，启动失败前后比较 user 数量、`userId`、手机号、角色、状态和密码哈希指纹。

| 数据与配置 | 预期 |
|---|---|
| `users=0`，合法裸号，12–128 位首次密码 | 创建 `A-01` 并启动 |
| `users=0`，首次密码为空、少于 12 位或超过 128 位 | 拒绝启动，不创建管理员 |
| `ADMIN_PHONE` 不是裸 11 位 | 配置校验拒绝启动 |
| 已有匹配的 `ACTIVE + ADMIN`，不设置 `ADMIN_PASSWORD` | 正常启动且零写入 |
| 已有匹配管理员，仍传任意 `ADMIN_PASSWORD` | 正常启动，变量被忽略 |
| 非空 `users` 缺少配置号码 | 拒绝启动，不自动创建或迁移 |
| 配置号码属于 `USER` | 拒绝启动 |
| 配置管理员为 `ADMIN_SUSPENDED`、`ERASED` 或密码哈希为空 | 拒绝启动 |
| 存在第二个非 `ERASED` 管理员 | 拒绝启动 |
| 另一个非 `ERASED` 账号占用固定 `+86` 等价号 | 拒绝启动 |
| 只改 `ADMIN_PHONE`，数据库未同步 | 拒绝启动 |
| 按第 6.2 节同时更新同一 userId、会话和配置 | 正常启动，仍只有一个管理员 |

当前实现不把 `ERASED` 账号计入等价号 owner 或第二管理员冲突；不得把历史 `ERASED` 记录写成当前启动阻断。

## 8. 并发和幂等专项

### `ADM-CONC-P1-01` 并行首次启动

在同一个全新隔离数据库上，以不同端口同时启动两个配置完全相同的服务实例。预期 guard 锁保证最终只创建一个 `A-01`；另一个实例在获得锁后验证同一行，两个实例最终都可就绪。

当前没有真正并行 Bootstrap 的自动化测试，必须保存本次实例日志、唯一 `userId`、账号数量和密码哈希指纹，不能用普通 MySQL 初始化测试冒充并发证据。

### `ADM-CONC-P1-02` 同一管理员双会话审核

在该专项的隔离副本中，执行 `SMOKE-03` 到医生身份申请已提交但尚未审核为止，不要先执行普通单次批准。保存同一个 `PENDING` 申请 ID，使用 `A-01` 的两个独立会话同时发送：

```http
PUT /api/admin/identity/applications/{applicationId}/review
Authorization: Bearer <S1 或 S2 access token>
Content-Type: application/json

{"decision":"APPROVED","reviewNote":""}
```

两个响应的无序结果必须恰好为：一个 HTTP `200`、body `code=200`；另一个 HTTP `400`、body `code=400`，提示只有待审核申请可以处理。最终该申请只形成一个 `APPROVED` 终态，只创建一份医生角色、医生档案和钱包，不重复创建任何副作用。

### `ADM-CONC-P1-03` 普通用户重复暂停/恢复

- 对 `PUT /api/admin/users/{userId}/deactivate` 并行发送两次：一个 HTTP `200`、body `code=200`；另一个 HTTP `200`、body `code=400` 并提示已停用。最终唯一状态为 `ADMIN_SUSPENDED`，旧 token 失效。
- 对 `PUT /api/admin/users/{userId}/reactivate` 并行发送两次：两次均可 HTTP `200`、body `code=200`。最终唯一状态为 `ACTIVE`，用户需重新登录。

### `ADM-CONC-P1-04` 固定管理员失败关闭

用两个 `A-01` 会话同时请求固定管理员暂停、恢复或在线换号：暂停/恢复使用管理用户端点，均应 HTTP `400`、body `code=400`；在线换号使用 `PUT /api/user/phone-change`，应 HTTP `200`、body `code=400`。管理员行不得发生部分写入。

### `ADM-CONC-P1-05` 重复退出

对同一个 refresh token 并行调用两次 `POST /api/auth/logout`，两次都可 HTTP `200`、body `code=200`；最终 token 只处于撤销状态，不产生新会话，绑定的 access token 不能继续访问管理接口。

## 9. 管理业务专项回归

这些用例不纳入默认空白部署主链路，只在相关模块变更或发布前选择执行：

| 专项 | 检查点 |
|---|---|
| 身份驳回 | 必须填写原因；不创建角色、档案、机构或钱包 |
| 重复身份审核 | 明确冲突，不重复创建副作用 |
| 专业身份撤销 | 只有 `A-01` 可执行；角色进入 `REVOKED`，按类型处理档案和机构关系 |
| 关系人工覆盖 | 只作为平台兜底，不替代法人正常 `JOIN/LEAVE` 审核 |
| 强制撤销关系 | 待审 JOIN/LEAVE 时冲突；成功时只处理目标关系 |
| 平台项目异常 | 无意见驳回、重复审核、非法价格和越权请求均失败关闭 |
| 全局订单 | 只验证全局读取范围，不把浏览器返回页当成支付事实 |
| 暂停账号身份审核 | 当前仍可能批准，记录为账号状态收口缺口 |
| 私有材料 | 普通用户不可访问，预览响应不可缓存 |
| 普通 token 探测管理接口 | 当前并非全部失败关闭：部分历史 `/api/admin/**` 只要求 authenticated，`GET /api/admin/projects` 可用普通 token 返回全局数据；必须记录为 P0 越权缺口，不能把 `200` 误记为通过 |

管理员撤销医生或顾问身份会撤销该角色的机构关系；撤销法人身份不会自动停用机构，也不会删除机构内医生、顾问、项目或历史订单。

## 10. 自动化证据映射

| 能力 | 当前证据 |
|---|---|
| Bootstrap 创建、无密码重启、配置漂移、异常管理员、等价号和第二管理员 | `AdminAccountCommandServiceTest` |
| MySQL 现有管理员无密码启动、第二个非注销管理员和等价号 | `AdminAccountCommandServiceMySqlIntegrationTest` |
| 管理登录、普通入口隔离和注册保留号码 | `AuthenticationServiceTest` |
| 固定管理员在线换号、普通账号首次绑定和发送保留号验证码 | `UserProfileServiceSecurityTest` |
| 管理登录统一 401、限流、退出与会话 | `AdminAuthControllerTest`；格式校验保留手工证据 |
| 旧角色接口不可用 | `AdminUserControllerTest` |
| 固定管理员暂停、恢复和在线换号保护，普通账号最终换绑保留号码 | `AdminAccountCommandServiceTest` |
| 固定管理员注销 blocker | `LocalAccountDeletionBlockerServiceTest`、`AccountDeletionCoordinatorTest` |
| 管理端角色只读、无角色请求、固定管理员无暂停/恢复入口 | `UserAccountStatePages.test.tsx` |

以下项目当前必须保留手工证据，不能声称已有专用自动化覆盖：成功的停机改号事务、真正并行 Bootstrap、管理员改密的完整双会话端到端验证。

## 11. 已知边界

| 编号 | 状态 |
|---|---|
| `ADMIN-BLOCK-01` 关键管理操作缺少统一不可篡改审计 | 仍是管理控制面风险，局部日志不能替代统一审计 |
| `ADMIN-BLOCK-02` 裸号与 `+86` 等价号 | 按单一固定管理员规则关闭；当前启动冲突只统计非 `ERASED` owner |
| `ADMIN-BLOCK-03` 管理员关系页不是正常 JOIN/LEAVE 入口 | 保留；标准关系审核仍由机构法人执行 |
| `ADMIN-BLOCK-04` 部分历史管理接口只要求 authenticated | 保留，普通 token 可读取或修改全局数据的端点属于 P0 越权，收口前不得放行管理控制面 |
| `ADMIN-BLOCK-05` 暂停账号的身份申请仍可能被批准 | 保留，修复前不得忽略 |
| `ADMIN-BLOCK-06` 顾问缺少完整订单工作台、真实支付未生产验收 | 保留；管理员全局查看不代表三方履约闭环 |
| `ADMIN-BLOCK-07` 单一固定管理员 | 已关闭；动态管理员生命周期不是产品能力 |

本期不启用分账；相关配置、提案、比例和收益验收统一为 `N/A`。

## 12. 证据与放行标准

每个默认主链路或所选管理员专项至少记录：用例编号、数据库目标、服务版本、管理员 `userId`、操作前后状态、HTTP 状态与业务 `code`、执行时间、结论、缺陷编号和证据编号。

必须保留：

1. 数据库主机、数据库名和 active profiles；
2. 首次创建与无密码重启的账号快照和哈希指纹对比；
3. 首次登录、普通入口隔离和错误矩阵；
4. 管理端角色只读、固定管理员无暂停/恢复入口；
5. 固定管理员暂停、恢复、在线换号、注销和保留号码失败证据；
6. 改密后的双会话失效和服务端退出结果；
7. 启动失败矩阵的零写入对比；
8. 若执行改号，保存备份标识、事务影响行数、新旧登录和旧会话失效证据；
9. 同一 `A-01` 双会话并发证据。

跨角色最小主链路放行必须同时满足：

- 总流程 `SMOKE-00`～`SMOKE-08` 全部通过，其中 `ADM-P0-00`～`ADM-P0-04` 的管理员细节证据完整；
- `ADMIN_PASSWORD` 已从部署环境移除，当前密码仍可登录；
- 始终只有一个与 `ADMIN_PHONE` 一致的 `ACTIVE + ADMIN`；
- 角色修改接口不可用，管理端不发送角色修改请求；
- 选中的专项没有未解释失败；
- 已知边界被如实记录，没有把环境阻断、手工证据或 N/A 写成自动化通过。

只有在额外声明“管理员生命周期专项通过”时，才继续要求 `ADM-ACCOUNT-P1-01`～`03`、`ADM-SEC-P1-01` 以及本轮选中的启动失败、并发和改号专项全部通过；其中必须证明固定管理员不能暂停、恢复、在线换号或注销，且改密和成功的服务端退出能使旧会话失效。
