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
