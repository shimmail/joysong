# UAT Runner 元数据隔离与首次部署续行

本轮从已合并的 `ad699e12` 继续，保留既有部署主路径。用户已准备两份受控配置并授权继续部署；OSS 使用实例已有角色，不创建或修改 Bucket、角色或凭据。

## 最小修复范围

- 只修改 Ubuntu bootstrap 的 Runner 安装和验收：服务限制 ECS 元数据访问，生成固定 root-owned 启动探针。
- 独立应用用户服务在 Runner 负向检查前后完成 IMDSv2 正向检查；网络故障不能单独作为隔离成功证据。探针不读取角色凭据正文。
- 注册前运行同等隔离环境预验证；每次启动执行探针；二次初始化拒绝探针、已加载规则或启动成功状态漂移。
- 测试只涉及部署脚本，不修改 Backend、Admin、数据库迁移或用户现有工作区改动。

## 执行顺序

1. 完成定向隔离测试和 bootstrap 相关测试，再运行一次部署测试集合、现有静态契约检查。
2. 同步部署指南、配置字典和 UML 源码；创建 PR，验证 PR head 和 merge SHA 的 `Required quality gates`。
3. 重新校验受控配置、ECS fresh 状态、官方 Runner 版本及 SHA；临时生成 Runner 注册 token。
4. 使用已合并源码完成 bootstrap、二次一致性复核、Runner online 和权限检查。
5. 选择未用过的 UAT tag，等待制品、部署、Draft Release、Acceptance Issue；首次成功后移除两份配置中的 `ADMIN_PASSWORD` 并重启复验。
6. 清理本轮临时验证文件，保留发布证据；不得覆盖、删除或 reset 既有数据库。

## 当前执行事实

- 2026-09-06：实例 Ubuntu 24.04.4 / systemd 255，内核支持 cgroup BPF；不受限进程可访问 IMDS，同地址拒绝规则表现为丢包超时，需要配对正向检查。
- GitHub 默认分支原为无共同祖先的旧 `main` 初始化提交，已对齐批准的 `master`；旧分支内容保留。
- 当前 GitHub 套餐对私有仓库分支保护 API 返回 403（要求 Pro）；未开通付费服务。PR head 和 merge SHA 仍按既有发布流程逐一验证，不能声称服务器端分支保护已启用。
- 初始化、tag 和业务验收结果在实际执行后更新，不以本地配置或测试结果替代。
- 本地部署集合 73 项通过、5 项因 Windows 平台跳过；相关测试夹具只读常量问题已定向修复，Linux CI 继续覆盖平台用例。Windows 默认 GBK 的测试子进程读取曾产生编码诊断，测试集合最终成功，不进行无依据重试。
- ShellCheck 对 Git/Linux 使用的 LF 字节流检查通过；工作区 CRLF 及 Windows text-mode stdin 会触发 SC1017，检查采用二进制输入避免换行转换。
- ECS 临时服务实证：`ExecStartPre=+` 保留 Runner 的 IP 拒绝规则，应用 IMDSv2 前后成功、Runner 连接被阻断，探针退出 0；fresh-host preflight 通过。
- 官方 Runner 归档初次下载截断，续传后大小 `226430031` 字节及固定 SHA-256 均匹配，未修改批准版本或摘要。
- PR #7 已合并为 `d3cfeaba`，head 门禁 `34017142293`、merge 门禁 `34017207382` 均成功。
- 实际 apply 在安装依赖前暴露官方归档六个合法工具符号链接与旧全拒绝校验器冲突；同时发现 EXIT 钩子依赖失效的局部路径导致 token 未清理。已删除遗留 token，数据库、应用与 Runner 尚未安装；后续提交精确放行固定链接并冻结退出清理路径，补负向回归后重新过门禁。
- 归档及失败清理修复的 9 项新增/修改定向测试通过（4.784 秒），固定官方完整归档通过只读校验，ShellCheck 通过；不重复本地全量测试，Linux CI 继续验证合并准入。
- PR #8 已合并为 `860f2563`，head 门禁 `34017899619`、merge 门禁 `34017952308` 均成功。
- 后续实际 apply 已安装 Java 17、MySQL 8、Nginx，创建固定 UAT 空库和系统用户，配对元数据探针通过；在注册前发现官方服务脚本位于 `bin/runsvc.sh`。按官方 service template 的安装方式复制至 Runner 根目录，并验证内容、权限和属主。失败 token 已自动清理，Runner 尚未注册。
- 对该明确断点，只在已安装文件、归档内容、系统身份、权限、空数据库及无发布状态全部匹配后续行注册和服务安装；不重跑 fresh preflight 或数据库创建，不删除或重置数据库。续行后仍执行完整 host contract 验证与二次 apply 一致性检查。
- PR #9 已合并为 `3789a884`，head 门禁 `34018489430`、merge 门禁 `34018545714` 均成功；断点续行和二次 apply 验收成功，Runner ID 21 在线且仅具 `joysong-uat-deploy` 标签。
- 首个 tag `v0.0.1-uat.1` / run `34018740995` 完成 Backend、Admin 构建、安全扫描和 fresh preflight；在首次数据库备份时因 mysqldump 不支持 `[client] database` 被拒绝。后端未启动、数据库表数仍为 0、无 current/previous 或发布事务；失败备份保持 root-only 且没有完成标记。该 tag 不复用，修复后使用新 tag。
- 备份兼容修复只在受保护 staging 派生排除 `database` 的临时客户端配置，固定数据库参数和原秘密配置不变；验证真实 MySQL 8 客户端备份成功后再发下一候选版本。
- PR #10 已合并为 `06b333de`，head 门禁 `34019242839`、merge 门禁 `34019334091` 均成功。ECS 使用派生配置完成真实 MySQL 8 空库导出和 gzip 校验，更新后的主机契约复核成功。
- `v0.0.1-uat.2` / run `34019458732` 已完成备份、B33 + V34…V40 全部迁移、应用约 20 秒启动和管理员初始化；部署器健康等待超时后停用服务并保留事务，Backend current 保留、Admin current 尚无。数据库已迁移，不再具备 fresh 条件，禁止重建或清空以重试。
- 监听门禁原先整行搜索 IPv4 文本，无法识别 Java 的 IPv4-mapped IPv6 回环表示。修复只接受精确本地回环地址及同一 MainPID 的独占监听；恢复必须先核对原事务、制品、备份、配置和完整迁移历史，失败保留数据库与事务。旧 workflow 失败事实保留，恢复后的下一 tag 走 existing 正常发布。
