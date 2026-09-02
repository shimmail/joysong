# JoySong 阿里云独立 HTTPS Demo 部署指南

> 文档日期：2026-09-01
>
> 适用范围：供主管安装 App、访问管理后台并验收浏览、AI 咨询、翻译、图片和短信等能力的独立演示环境
>
> 部署形态：阿里云 ECS + RDS MySQL 8.0 + OSS/CDN + 短信服务 + Nginx HTTPS
>
> 本文只描述部署与验收，不包含业务代码修改，也不包含任何支付供应商密钥或历史敏感文档。

本文是阿里云远程 Demo 的执行基线。在阿里云 Demo 场景下，以本文为准；项目通用变量说明仍可参考 [CONFIGURATION_GUIDE.md](./CONFIGURATION_GUIDE.md)。

## 1. 先给结论

可以在阿里云建立一套与开发库、生产库完全隔离的 HTTPS Demo，向主管交付：

- 一个正式签名的 Android APK，固定连接 Demo HTTPS 域名；
- 一个可登录的管理后台 HTTPS 地址；
- 独立 RDS 数据库中的虚构机构、医生、顾问、平台项目和机构项目；
- OSS/CDN 图片访问；
- AI 咨询与当前翻译能力；
- 在阿里云资质、签名和模板审核通过后，用受控真实号码验收短信验证码。

但是，按当前仓库状态，不能把下列三项写成“部署后即可使用”：

| 能力 | 当前状态 | 上线前处理 |
| --- | --- | --- |
| 一键主管快照 | `Apply`、只读 `Verify`、重复执行零写入和安全 `demo` profile 已实现；隔离 MySQL 8.0.39 与真实脚本入口已验证 | 可导入独立 Demo 库；完整九步接口重放仍属后续阶段 |
| 公网 Demo 支付 | 安全 `demo` profile 已实现，但明确关闭真实支付和模拟支付 | 首次快速 Demo 不演示支付；禁止把 `dev` profile 暴露公网 |
| 新 OSS Bucket 上传 | 当前 Java SDK 版本具备 V4 能力，但 OSS 客户端没有显式启用 V4 签名和 region；新 Bucket 已不能使用 V1 | 部署前先改造 OssConfig，强制 HTTPS、V4 和正确 region，并对新 Bucket 完成上传/删除集成测试 |

因此本次优先交付“HTTPS、后台、角色登录、机构/医生/项目浏览”的非交易 Demo。目录快照使用 `docs/test/catalog-v1.json`，同一 JAR 和 `scripts/demo-data.ps1` 可在本地隔离 MySQL 或远程 RDS 上执行。AI、翻译、OSS 和短信只有在各自凭据及验收完成后再逐项放行；首次快速 Demo 不依赖这些外部能力。

### 1.1 最快安全交付路径

1. 预创建名称以 `myapp_worktree_` 开头的独立 MySQL 8.0 数据库，并在导入前创建可恢复快照。
2. 使用 `demo` profile 首次启动，完成 Flyway 和唯一管理员初始化；迁移前门禁会打印并核验实际主机与数据库名。
3. 将同一提交的发布 JAR 与 `docs/test/catalog-v1.json` 一并上传，在部署机执行 `pwsh -File scripts/demo-data.ps1 -Action Apply -JarPath <发布JAR绝对路径> -CatalogPath <catalog-v1.json绝对路径>`。
4. 再执行同一脚本的 `Verify`；成功标志为 `CATALOG_READY`、数据版本、SHA-256、`managed=378`。
5. 正常启动远程 API 时保留 `SPRING_PROFILES_ACTIVE=demo`，并让 `DEMO_DATA_ENABLED=false`，防止每次启动自动触发目录任务。
6. 需要撤销时恢复导入前 RDS 快照，或销毁该独立 Demo 数据库/实例；首阶段不执行对象级 Reset。

2026-09-02 本地隔离验收结果：严格目录测试通过；MySQL 集成测试验证后段故障时数据库和占位文件全部回滚，随后 `Apply created=378 managed=378`、`Verify created=0 managed=378`、重复 Apply 零写入；发布 JAR 的真实 `scripts/demo-data.ps1` Apply/Verify 入口均输出 `CATALOG_READY`。这证明工具可迁移到远程 MySQL，不替代 RDS、HTTPS、管理端和 APK 的远程验收。

## 2. 推荐拓扑

~~~text
主管 Android App ───────┐
主管浏览器/管理后台 ────┼── HTTPS 443 ── demo.example.com
公开分享页 ─────────────┘                    │
                                      阿里云 ECS
                                  Nginx + Admin dist
                                           │
                                  127.0.0.1:8080
                                      Spring Boot
                         ┌─────────────────┼──────────────────┐
                         │                 │                  │
                    RDS MySQL 8.0     OSS 私有 Bucket      阿里云短信
                    VPC 私网连接       内网写入              HTTPS API
                         │                 │
                    独立 Demo 库      CDN 私有回源
                                           │
                                img-demo.example.com
~~~

建议统一使用一个业务域名：

- demo.example.com：管理后台、App API、公开分享页；
- img-demo.example.com：OSS 图片的 CDN HTTPS 域名。

管理后台当前固定通过同源 /api 访问后端，并使用前端路由。因此 Nginx 必须同时承载管理端静态文件、/api 反向代理和 SPA fallback。Flutter 构建时把 API_BASE_URL 固定为 https://demo.example.com。

### 2.1 隔离原则

1. ECS、RDS、OSS、RAM 用户和域名均使用 Demo 专用资源或专用权限边界。短信 RAM 身份必须独立，但同一阿里云账号内的总量、频控和限额可能与其他环境共享；需要真正隔离时使用独立阿里云账号。
2. 优先使用独立 RDS 实例；预算受限时至少使用独立数据库、独立账号和独立备份，绝不能连接共享开发库或生产库。
3. RDS 只开放 VPC 内网地址，只允许 ECS 私网 IP；不创建公网数据库地址。
4. ECS 安全组只对公网开放 80、443；22 只允许固定运维 IP；8080、3306 不开放公网。
5. 当前验证码、登录限流、部分缓存和定时任务为单 JVM 状态，Demo 后端固定为一个实例，不直接水平扩容。
6. 所有演示业务数据必须虚构；真实短信只使用测试人员明确授权的真实号码，不能向虚构账号号码发送短信。

## 3. 部署前门禁

未满足本节时，不应向主管承诺交付日期。

### 3.1 域名、备案和证书

- 中国内地 ECS 对公网提供网站或 App/API 服务时，域名必须完成 ICP 备案；已在其他接入商备案的域名迁入阿里云时，还需按要求办理新增接入。
- 医疗、医美相关网站是否需要额外前置审批，应以备案页面、阿里云备案顾问和主管机关的实际审核要求为准。
- 网站和 App 按实际形态分别提交网站/App 信息；img-demo.example.com 使用中国内地或全球 CDN 加速时，加速域名也必须具备 ICP 备案。
- 上线后在网站页脚、公开分享页和 App 设置页按要求展示备案号，并在开通后按当前规定办理公安联网备案。
- 为 demo.example.com 和 img-demo.example.com 准备 DNS 解析与有效 TLS 证书。
- 证书私钥只保存在受控服务器目录，不进入 Git、APK、文档、聊天记录或截图。

如果需要使用本次 ECS 取得阿里云备案服务能力，应在购买前核对当前备案服务器条件；地域、付费方式、购买时长和公网带宽均可能影响资格，免费试用或不满足时长的实例不能直接作为备案服务器。以阿里云备案控制台的实时检查结果为准。

如果尚无可用于中国内地服务的已备案域名，这是公网 HTTPS Demo 的真实阻断项，不应通过裸 IP 或忽略证书错误的方式交付。

### 3.2 阿里云短信资质

阿里云国内短信需要完成账号实名认证、短信签名归属方资质、签名审核、运营商实名报备和模板审核。签名不能使用虚构演示机构名称，也不能用“测试”代替真实主体。个人认证账号的自用资质不能替代运营商所需企业资质，部署前应在短信控制台核对账号和资质类型。

特别注意：阿里云当前验证码模板规范将“整容、医美”列为禁止内容。建议只提交中性验证码文案，例如：

> 您的验证码为 ${code}，5分钟内有效，请勿泄露给他人。

验证码变量必须保持字面量 ${code}；阿里云当前要求验证码变量为 4 至 6 位数字或字母，当前应用生成 6 位数字，符合这一格式。

即使模板没有营销或医美词汇，仍需如实提交实际业务场景，并在排期前通过阿里云工单确认是否可用于本项目。签名审核后还要等待运营商实名报备完成，实际时间不作承诺；只有通道真实发送成功后，才能将短信标记为可交付。

### 3.3 当前仓库能力门禁

- prod 启动强制要求数据库、JWT、Google Client ID、OSS、短信、分享地址和 AI Agent 配置完整。
- 健康检查只代表 Spring 和基础数据库链路可用，不代表 OSS、短信、AI 或翻译成功。
- OSS 当前只负责公共图片；身份材料、退款证据等私有文件仍保存在 ECS 私有目录。
- OSS 和短信当前实现使用固定 RAM AccessKey，不支持 ECS 实例 RAM 角色或默认凭据链。
- OSS 客户端当前没有显式设置 V4 签名和 region。阿里云自 2025-09-01 起不再允许新 Bucket 使用 V1，因此新 Demo Bucket 上传是代码级阻断项。
- 演示目录 Apply/Verify 和安全 `demo` profile 已落地并通过隔离 MySQL；远程 RDS 与公网冒烟仍以最新验收记录为准。
- `demo` profile 不支持任何真实或模拟支付，公网环境禁止使用 `dev` profile。

## 4. 阿里云资源清单与命名

实际地域按账号、备案和访问人群选择；ECS、RDS、OSS 应位于同一中国内地地域。

| 资源 | 建议命名 | 关键配置 |
| --- | --- | --- |
| 资源组 | joysong-demo | 将 Demo 账单和权限与其他环境分开 |
| VPC | joysong-demo-vpc | ECS 与 RDS 同 VPC |
| 交换机 | joysong-demo-vswitch | 与 ECS、RDS 可用区规划一致 |
| ECS | joysong-demo-api-01 | Ubuntu 22.04/24.04、Java 17、Nginx，单实例 |
| 安全组 | joysong-demo-sg | 22 限运维 IP；80/443 公网；不放行 8080/3306 |
| RDS | joysong-demo-mysql | MySQL 8.0、内网连接、自动备份 |
| 数据库 | myapp_worktree_demo_202609 | 预创建、utf8mb4、独立账号；名称必须带安全前缀 |
| OSS Bucket | joysong-demo-media-<地域>-<随机后缀> | 私有、阻止公共访问、服务端加密 |
| CDN 域名 | img-demo.example.com | HTTPS、私有 OSS 回源授权 |
| RAM 用户 | joysong-demo-oss-uploader | 仅目标 Bucket 的写入和删除权限 |
| RAM 用户 | joysong-demo-sms-sender | 仅发送短信权限 |
| 短信签名/模板 | 真实主体签名/中性验证码模板 | 审核通过后才配置 |

资源统一增加标签：

- app=joysong
- env=demo
- owner=<负责人>
- expires=<计划下线日期>

同时创建预算告警，至少覆盖 ECS、RDS、OSS/CDN 流量、短信发送量和 AI 调用量。

## 5. 创建 VPC、ECS 与安全组

### 5.1 ECS 建议

短期主管 Demo 可从 2 vCPU、4 GiB 内存起步；磁盘容量按日志和私有上传文件预留。不要把该规格当作正式生产容量结论，实际以云端压测为准。

ECS 需要：

- Java 17 JRE；
- PowerShell 7（仅用于执行仓库提供的跨平台 `demo-data.ps1`；标准 Ubuntu 不预装）；
- Nginx；
- MySQL 客户端，仅用于受控连通性检查；
- chrony 或系统时间同步；
- Asia/Shanghai 主机和 JVM 时区；
- 独立系统用户 joysong-demo，不以 root 运行 Java。
- 独立 EIP 或其他稳定公网入口；替换 ECS 时要同步验证 EIP 绑定和 DNS。

Ubuntu 初始化示例：

~~~bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jre-headless nginx mysql-client unzip
sudo timedatectl set-timezone Asia/Shanghai

sudo useradd --system --user-group --no-create-home --home-dir /opt/joysong-demo --shell /usr/sbin/nologin joysong-demo

sudo install -d -o joysong-demo -g joysong-demo -m 0750 /opt/joysong-demo/releases
sudo install -d -o root -g www-data -m 0750 /var/www/joysong-demo/releases
sudo install -d -o joysong-demo -g joysong-demo -m 0750 /var/lib/joysong-demo/uploads
sudo install -d -o joysong-demo -g joysong-demo -m 0700 /var/lib/joysong-demo/private
sudo install -d -o root -g joysong-demo -m 0750 /etc/joysong-demo
~~~

### 5.2 安全组

入方向只配置：

| 端口 | 来源 | 用途 |
| --- | --- | --- |
| 22/TCP | 运维固定公网 IP/32，或堡垒机安全组 | SSH |
| 80/TCP | 0.0.0.0/0、需要时 ::/0 | 仅跳转 HTTPS 和证书验证 |
| 443/TCP | 0.0.0.0/0、需要时 ::/0 | App、后台、分享页 |

不要增加 8080 或 3306 公网规则。Spring Boot 只监听 127.0.0.1:8080。

### 5.3 私有文件数据盘

当前身份材料和退款证据不在 OSS，而是写入 /var/lib/joysong-demo/private。不能只把该目录留在可随 ECS 释放的临时文件系统中：

1. 为 /var/lib/joysong-demo 准备独立加密 ESSD 数据盘。
2. 只对确认的新空盘执行初始化，按阿里云数据盘指南分区和格式化。
3. 通过文件系统 UUID 写入 /etc/fstab，并在重启后确认自动挂载。
4. 挂载成功后再创建 uploads 和 private 目录及权限，避免目录先写入系统盘。
5. 配置每日自动快照，保留 7 至 14 天；发版前创建手工快照。
6. 至少把一个快照恢复到新数据盘并验证私有文件读取。

RDS 备份不包含 ECS 数据盘，ECS 快照也不包含 RDS；两套恢复流程必须分别验收。运维使用 SSH 密钥登录，关闭 root 远程登录和密码登录，并确认主机防火墙与安全组的开放端口一致。

## 6. 创建独立 RDS MySQL 8.0

### 6.1 网络和账号

1. 创建与 ECS 同地域、同 VPC 的 RDS MySQL 8.0。
2. 关闭或不申请公网连接地址。
3. RDS 只选择一种最小范围入口：ECS 专用私网 IP 白名单，或在支持的地域关联专用 ECS 安全组。白名单与安全组规则按并集生效，必须清理其他白名单组，禁止 0.0.0.0/0；关联安全组时要确认组内没有无关 ECS。
4. 预创建数据库 `myapp_worktree_demo_202609`（实际后缀按环境命名），字符集 utf8mb4。
5. 创建 joysong_demo_app 账号，只授权该独立 Demo 数据库。

当前 Flyway 和运行时共用一个数据库账号，迁移中包含存储过程操作。该账号除 DML 外，还需要目标 schema 内的 CREATE、ALTER、DROP、INDEX、REFERENCES、CREATE ROUTINE、ALTER ROUTINE、EXECUTE 等权限，但不得拥有全局权限、CREATE USER 或 GRANT OPTION。这是当前版本的过渡性例外；后续应拆分一次性迁移凭据和长驻应用凭据，避免应用进程长期持有 DROP、ALTER 和例程权限。

DB_URL 不得包含 createDatabaseIfNotExist=true。

### 6.2 TLS

RDS 开启 SSL。最低基线可使用 sslMode=REQUIRED 保证加密，但它不校验 CA 和主机名；正式交付前更推荐按阿里云 RDS 证书指南下载 CA、导入应用专用 JVM truststore，并改为 sslMode=VERIFY_IDENTITY。必须使用 RDS 官方内网 Endpoint，不能换成裸 IP 或自定义别名。

不要直接修改会被 JRE 升级覆盖的系统 cacerts。应用专用 truststore 需要保留正常公共 CA 信任，否则 Google、OSS、短信和 DashScope 的 HTTPS 调用可能一起失败；同时记录 RDS CA 到期更新流程。只有完成证书链和主机名验证后，才能在验收报告中写“数据库身份已校验”。

示例连接串：

~~~dotenv
DB_URL=jdbc:mysql://<RDS内网地址>:3306/myapp_worktree_demo_202609?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&sslMode=REQUIRED
DB_USERNAME=joysong_demo_app
DB_PASSWORD=<数据库强密码>
DEMO_DATABASE_NAME=myapp_worktree_demo_202609
~~~

### 6.3 备份与恢复

- 配置每日自动全量备份，保留 7 至 14 天；同时开启日志备份和时间点恢复，并确认两者保留期一致。
- 首次迁移、导入演示数据和每次发版前分别创建手工备份。
- 禁止在远程 Demo 运行 Flyway clean。
- 需要恢复主管基线时，按 RDS 全量恢复流程恢复到新 RDS 实例，验证后切换 DB_URL；不要直接清空当前库。只有控制台明确支持且已演练过库表恢复时，才使用更小粒度恢复。
- 恢复演练至少执行一次，并记录恢复耗时、数据库名和校验结果。

在任何迁移或恢复动作前，先在变更记录中打印并人工确认：

~~~text
数据库主机：<RDS内网地址>
数据库名称：myapp_worktree_demo_202609
环境：阿里云独立 Demo
~~~

## 7. 配置 OSS 公共图片

### 7.1 目标方案与代码前置条件

当前 OssConfig 直接以 Endpoint、AccessKey ID 和 AccessKey Secret 构造 OSS Java SDK 客户端，没有显式配置 Signature V4 和 region。阿里云已规定新 Bucket 不再支持 Signature V1，因此创建新 Demo Bucket 后很可能出现签名错误。部署前必须先完成以下最小代码改造并测试：

1. 为 OSS 增加明确的 region 配置，例如 cn-hangzhou，且与 Bucket 地域一致。
2. 使用 ClientBuilderConfiguration 显式设置 SignVersion.V4。
3. 使用 HTTPS Endpoint，并保持证书校验开启。
4. 在一个新建私有 Bucket 上验证 putObject 和 deleteObject。

这不是可以靠环境变量完全绕过的配置问题。仅把 Endpoint 改成 HTTPS 仍不能补上 V4 的 region 和签名配置。

采用“私有 OSS Bucket + CDN 私有回源授权”：

1. 创建 Demo 专用私有 Bucket，开启阻止公共访问。
2. 开启 SSE-OSS 服务端加密。
3. 后端使用带 https:// 的同地域 internal Endpoint 上传，避免图片写入绕公网。
4. 在 CDN 添加 img-demo.example.com，完成首次域名归属验证，业务类型选择图片和小文件。
5. CDN 源站选择同账号 OSS Bucket 或其公网 OSS Endpoint；CDN 回源不能填写 internal Endpoint。
6. 开启 Alibaba Cloud OSS Private Bucket Access，优先使用同账号 STS 授权。
7. 将回源端口和回源协议设为 443/HTTPS，为 CDN 域名配置客户端 HTTPS 证书。
8. 按控制台给出的 CNAME 完成 DNS 解析，从手机蜂窝网络验证。
9. 将 OSS_PUBLIC_BASE_URL 设置为 https://img-demo.example.com。

完成上述 V4/HTTPS 客户端改造后，该方案与现有文件 URL 模型兼容：后端经内网写入 OSS，数据库保存 CDN 公网 URL，App 和后台通过 HTTPS 读取图片。

不能仅把 Bucket 设为私有后直接填写 OSS 内网地址。当前代码不会生成临时签名 URL，客户端也无法访问 internal Endpoint。

私有回源只保护 OSS 源站，CDN 缓存 URL 默认仍可公开访问。因此该 Bucket 只能保存允许公开展示的图片，并设置带宽封顶、流量和费用告警。需要防盗链时，应在验证 App、后台和分享页兼容性后再选择 Referer 或 URL 鉴权；私有身份材料不得放入这条路径。

### 7.2 最小 RAM 权限

当前代码需要固定 AccessKey。创建只用于 Demo OSS 的 RAM 编程用户，不使用阿里云主账号 AccessKey，也不授予 OSS FullAccess。

示例自定义策略，替换 Bucket 名：

~~~json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "oss:PutObject",
        "oss:DeleteObject"
      ],
      "Resource": [
        "acs:oss:*:*:joysong-demo-media-<地域>-<随机后缀>/*"
      ]
    }
  ]
}
~~~

AccessKey 只写入 ECS 的 root 可读环境文件。App、管理后台、Git、APK、测试账号文档中均不得出现。

后续代码改造应切换到 ECS 实例 RAM 角色或默认凭据链，再删除长期 AccessKey；当前版本尚不支持，本文不把它列为已完成能力。

### 7.3 公共与私有文件边界

| 文件类型 | 当前存储 | 公网访问 |
| --- | --- | --- |
| 机构、医生、项目等公共图片 | OSS，经 CDN | 可以 |
| 本地历史公共上传回退 | /var/lib/joysong-demo/uploads | 仅在显式配置 Nginx /images 时可以 |
| 身份材料、退款证据等私有文件 | /var/lib/joysong-demo/private | 绝不允许 Nginx alias 或 OSS 公共 URL |

私有目录必须使用持久云盘、0700 权限和独立备份。若以后要把私有文件迁移到 OSS，需要另行实现私有 Bucket、授权下载或短时签名 URL，不能复用公共 CDN 路径。

### 7.4 OSS 运营设置

- 设置生命周期规则，按 Demo 保留期清理废弃对象和未完成分片。
- 配置流量、请求数和费用告警。
- 不开启公共写入。
- 当前由后端上传，不需要为 App 配置 OSS 跨域上传。
- 图片单文件当前上限为 10 MiB，Nginx 请求体上限保持 52 MiB。

## 8. 配置阿里云短信验证码

### 8.1 开通步骤

1. 完成阿里云账号实名认证，并在短信控制台提交真实签名归属方企业资质。
2. 使用真实、合规的企业主体申请短信签名。
3. 等待签名审核和运营商实名报备完成。
4. 申请只包含字面量 ${code} 的中性验证码模板。
5. 通过工单确认本项目真实业务场景是否允许发送。
6. 创建 Demo 专用短信 RAM 编程用户。
7. 配置发送量预警值和限额值，再在费用中心配置独立费用告警。
8. 只使用经授权的真实测试号码完成验收；如使用快速学习与测试流程，先在控制台绑定号码，同账号最多绑定 5 个测试号码。

虚构 Demo 账号号码不能用于真实短信发送，因为随机号码可能属于真实个人。主管浏览和角色切换使用密码账号；短信验收单独使用一个部署时登记、验收后移除的真实号码。

### 8.2 最小 RAM 权限

~~~json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "dysms:SendSms"
      ],
      "Resource": [
        "*"
      ]
    }
  ]
}
~~~

如已确认 ECS 固定出口 EIP，可再增加 acs:SourceIp 条件。OSS 和短信使用两个不同 RAM 用户，便于独立轮换和停用。

### 8.3 当前代码行为和保护

- 手机号以 E.164 格式发送，例如 +86<11位授权号码>；阿里云 SendSms 支持 +86 中国大陆号码。
- 验证码为 6 位、5 分钟有效、同号码 60 秒内不能重复发送、最多 5 次错误尝试。
- 验证码和限频保存在单实例内存中，服务重启后失效；当前不支持多实例共享。
- API 不返回明文验证码，prod 不允许在日志输出验证码。
- /api/auth/send-code 是公开接口，另外两个已登录接口也能发送换绑验证码；应用内没有验证码、人机校验、IP 级总量保护。必须对三个发送端点统一配置 Nginx 限流，并设置阿里云阈值和费用告警。
- SendSms 接口返回成功只代表阿里云受理，不等于手机已送达。验收以阿里云控制台送达报告和测试手机实收为准。
- 阿里云当前默认同签名、同号码验证码限制为 1 条/分钟、5 条/小时、10 条/日，同号码跨发送方最多 40 条/日；请求被受理后即可能计入频控，即使最终未送达。上线前以控制台当前规则复核，不能把云侧限制当成应用防刷的替代品。
- 日/月发送总量默认不等于自动封顶，必须同时设置消息发送量预警和限额；达到限额后会影响同账号短信发送。同账号多个环境可能互相占用阈值。

## 9. 准备发布制品

### 9.1 安全构建

必须从干净检出或受控 CI 构建已审阅提交，不要从包含本机忽略配置的工作目录直接打包。`bootJar` 已排除 `application-dev.yml`、环境文件和常见密钥/证书容器格式，但发布前仍须执行下方内容扫描，防止后续构建配置回退。

只生成和上传：

- Spring Boot 可执行 JAR；
- joysong-admin/dist 静态文件压缩包；
- 正式签名 Android APK；
- 三个制品的 SHA-256；
- 不含密码的版本说明。

禁止上传整个仓库、doc、docs、本机环境文件、数据库导出、密钥文件或任何无关文档。

Windows/PowerShell 构建示例：

~~~powershell
Set-Location joysong-server
.\gradlew.bat test
.\gradlew.bat clean bootJar
Set-Location ..\joysong-admin
npm ci
npm run lint
npm test
npm run build
Compress-Archive -Path dist\* -DestinationPath joysong-admin-dist.zip -Force
Get-FileHash joysong-admin-dist.zip -Algorithm SHA256
Set-Location ..\joysong-flutter
flutter pub get
flutter analyze
flutter test
flutter build apk --release --dart-define=APP_ENV=production --dart-define=API_BASE_URL=https://demo.example.com --dart-define=GOOGLE_SERVER_CLIENT_ID=<Web OAuth Client ID>
~~~

按项目测试规则，每类全量测试最多执行一次；超过 10 分钟时停止并记录进度和最慢用例，不要无限重试环境型失败。

Android 必须配置项目的 release keystore。不要向主管发送 debug 签名安装包，也不要把 keystore 或密码放进交付目录。

当前仓库不会自动忽略 android/key.properties、JKS 或 keystore 文件。优先由 CI 密钥存储在构建时临时生成签名配置并在构建后删除，keystore 保存在仓库外；在补充项目级忽略规则之前，不要在工作区长期保存这些文件。

构建完成后必须用 Android SDK Build Tools 的 apksigner 验证，不能仅凭文件名判断已经签名：

~~~powershell
apksigner verify --verbose --print-certs build/app/outputs/flutter-apk/app-release.apk
Get-FileHash build/app/outputs/flutter-apk/app-release.apk -Algorithm SHA256
~~~

### 9.2 JAR 内容检查

发布前列出 JAR 内容，确认不存在：

- application-dev.yml；
- .env 或 AccessKey；
- 本机数据库密码、JWT 密钥、模型密钥；
- doc、docs 或 Word 文档；
- 其他非运行时文件。

可执行检查示例，先把实际 JAR 路径赋给 RELEASE_JAR：

~~~powershell
$RELEASE_JAR = Get-ChildItem joysong-server\build\libs\*.jar | Where-Object Name -NotMatch '-plain\.jar$' | Select-Object -First 1
$BLOCKED_ENTRIES = jar tf $RELEASE_JAR.FullName | Select-String 'application-dev\.yml|(^|/)\.env(?:\.[^/]+)?($|/)|\.(docx|pem|key|jks|keystore|p12|pfx)$'
if ($BLOCKED_ENTRIES) { $BLOCKED_ENTRIES; throw '发布 JAR 含禁止文件' }
Get-FileHash $RELEASE_JAR.FullName -Algorithm SHA256
~~~

Flutter APK 只能包含 API 公网地址、公开 OAuth Client ID 等客户端允许值，不得包含数据库、OSS、短信或 AI 服务端密钥。

## 10. 服务端环境变量

创建 /etc/joysong-demo/joysong.env，属主 root、属组 joysong-demo、权限 0640。所有尖括号内容必须替换，不能原样启动。

~~~dotenv
SPRING_PROFILES_ACTIVE=demo

SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8080
SERVER_BASE_URL=https://demo.example.com
APP_SHARE_BASE_URL=https://demo.example.com/s/diary/
CORS_ALLOWED_ORIGINS=https://demo.example.com

SERVER_FORWARD_HEADERS_STRATEGY=native
SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|::1
SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER=X-Forwarded-For
SERVER_TOMCAT_REMOTEIP_PROTOCOL_HEADER=X-Forwarded-Proto
SERVER_TOMCAT_REMOTEIP_HOST_HEADER=X-Forwarded-Host
SERVER_TOMCAT_REMOTEIP_PORT_HEADER=X-Forwarded-Port

TZ=Asia/Shanghai
JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai

DB_URL=jdbc:mysql://<RDS内网地址>:3306/myapp_worktree_demo_202609?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&sslMode=REQUIRED
DB_USERNAME=joysong_demo_app
DB_PASSWORD=<数据库强密码>
DEMO_DATABASE_NAME=myapp_worktree_demo_202609
DEMO_DATA_ENABLED=false
# 只在执行 Apply/Verify 命令时临时注入，不长期写入服务环境文件：
# DEMO_ACCOUNT_PASSWORD=<12至128字符的演示账号密码>

JWT_SECRET=<至少32字符的随机密钥>
GOOGLE_CLIENT_ID=<Web OAuth Client ID>
ADMIN_PHONE=<管理员11位大陆手机号>
ADMIN_PASSWORD=<仅首次空库启动时临时填写的12至128字符强密码>

UPLOAD_LOCAL_DIR=/var/lib/joysong-demo/uploads
UPLOAD_PRIVATE_DIR=/var/lib/joysong-demo/private

OSS_ENABLED=false
SMS_ENABLED=false
PAYMENT_RECONCILIATION_ENABLED=false
ALIPAY_PLUS_AUTO_PAY_ON_ORDER_CREATE_ENABLED=false
ALIPAY_PLUS_SIMULATED_ENABLED=false
STRIPE_LEGACY_ENABLED=false

AI_AGENT_PROVIDER=qwen
AI_AGENT_API_KEY=<Demo AI Agent Key>
AI_AGENT_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AI_AGENT_MODEL=<已验证模型 ID>
AI_AGENT_INTENT_MODEL=<已验证意图模型 ID>

TRANSLATION_PROVIDER=qwen
TRANSLATION_API_KEY=<Demo Translation Key>
TRANSLATION_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
TRANSLATION_MODEL=<当前已验证模型 ID>
~~~

后端 GOOGLE_CLIENT_ID 与 Flutter 构建参数 GOOGLE_SERVER_CLIENT_ID 必须是同一个 Web OAuth Client ID。若验收 Google 登录，还要在 Google Cloud 配置 Android OAuth Client：包名使用当前 com.joysong.app，SHA-1 使用 apksigner 输出的 release 证书指纹；改用独立 Demo applicationId 后必须创建对应的新 Android Client。

设置权限：

~~~bash
sudo chown root:joysong-demo /etc/joysong-demo/joysong.env
sudo chmod 0640 /etc/joysong-demo/joysong.env
~~~

ADMIN_PASSWORD 只用于空库首次创建管理员。管理员创建成功并确认可登录后，从环境文件删除该行并重启服务。ADMIN_PHONE 保留。

不在此环境配置任何支付供应商密钥。`demo` profile 会同时关闭真实和模拟资金流；不能通过打开本地开发开关绕过。

## 11. 使用 systemd 运行 Spring Boot

先把单个 JAR 上传为 /tmp/joysong-server.jar。每次使用新的版本目录，并以软链接切换：

~~~bash
RELEASE_ID=20260901-001
sudo install -d -o joysong-demo -g joysong-demo -m 0750 /opt/joysong-demo/releases/$RELEASE_ID
sudo install -o joysong-demo -g joysong-demo -m 0640 /tmp/joysong-server.jar /opt/joysong-demo/releases/$RELEASE_ID/joysong-server.jar
sudo ln -s /opt/joysong-demo/releases/$RELEASE_ID /opt/joysong-demo/current.next
sudo mv -Tf /opt/joysong-demo/current.next /opt/joysong-demo/current
~~~

示例服务文件保存为 /etc/systemd/system/joysong-demo.service：

~~~ini
[Unit]
Description=JoySong isolated demo backend
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=joysong-demo
Group=joysong-demo
WorkingDirectory=/opt/joysong-demo/current
EnvironmentFile=/etc/joysong-demo/joysong.env
ExecStartPre=/usr/bin/test -r /opt/joysong-demo/current/joysong-server.jar
ExecStart=/usr/bin/java -Xms512m -Xmx1536m -jar /opt/joysong-demo/current/joysong-server.jar
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
UMask=0027

NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/joysong-demo/uploads /var/lib/joysong-demo/private

[Install]
WantedBy=multi-user.target
~~~

首次创建服务文件后设置 root:root、0644，并再执行下面的启动命令。后续发布必须按第 16 节切换软链接并重启，不能只覆盖正在运行的 JAR。

启动前打印 RDS 内网主机和数据库名并由第二人确认，然后执行：

~~~bash
sudo chown root:root /etc/systemd/system/joysong-demo.service
sudo chmod 0644 /etc/systemd/system/joysong-demo.service
sudo systemctl daemon-reload
sudo systemctl enable --now joysong-demo
sudo systemctl status joysong-demo --no-pager
sudo journalctl -u joysong-demo -n 200 --no-pager
timeout 120s bash -c 'until curl --fail --silent http://127.0.0.1:8080/actuator/health >/dev/null; do sleep 2; done'
curl --fail http://127.0.0.1:8080/actuator/health
~~~

日志中不能出现密码、AccessKey、验证码或完整 Token。

## 12. Nginx、管理后台与 HTTPS

### 12.1 短信接口限流区

在 Nginx 的 http 上下文中增加独立文件，例如 /etc/nginx/conf.d/joysong-demo-rate-limit.conf：

~~~nginx
limit_req_zone $binary_remote_addr zone=joysong_demo_sms:10m rate=5r/m;
~~~

### 12.2 HTTPS 站点

将管理端 dist 解压到 /var/www/joysong-demo/current。创建 /etc/nginx/tls 时使用 root:root、0700；证书链文件使用 0644，私钥使用 0600。配置证书到期告警，续期时先备份，再替换、执行 nginx -t、重载并从外部真机验证。

下面示例需要替换证书路径；健康检查默认不向公网开放。先增加拒绝未知 Host/SNI 的默认站点，避免公网 IP 或任意域名命中 Demo：

~~~nginx
upstream joysong_demo_backend {
    server 127.0.0.1:8080;
    keepalive 32;
}

server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;
    return 444;
}

server {
    listen 443 ssl default_server;
    listen [::]:443 ssl default_server;
    server_name _;
    ssl_certificate     /etc/nginx/tls/demo.example.com.pem;
    ssl_certificate_key /etc/nginx/tls/demo.example.com.key;
    return 444;
}

server {
    listen 80;
    listen [::]:80;
    server_name demo.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name demo.example.com;

    ssl_certificate     /etc/nginx/tls/demo.example.com.pem;
    ssl_certificate_key /etc/nginx/tls/demo.example.com.key;
    ssl_protocols TLSv1.2 TLSv1.3;

    root /var/www/joysong-demo/current;
    index index.html;
    client_max_body_size 52m;

    add_header X-Content-Type-Options "nosniff" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;

    location ~ ^/api/(auth/send-code|user/phone-change/send-(current|new)-code)$ {
        limit_req zone=joysong_demo_sms burst=2 nodelay;
        limit_req_status 429;

        proxy_pass http://joysong_demo_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port $server_port;
    }

    location ~ ^/api/chat/sessions/[^/]+/messages/stream$ {
        proxy_pass http://joysong_demo_backend;
        proxy_http_version 1.1;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 75s;
        proxy_send_timeout 75s;
        add_header X-Accel-Buffering "no" always;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port $server_port;
    }

    location /api/ {
        proxy_pass http://joysong_demo_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port $server_port;
    }

    location ^~ /s/diary/ {
        proxy_pass http://joysong_demo_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port $server_port;
    }

    location ^~ /legal/ {
        proxy_pass http://joysong_demo_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port $server_port;
    }

    location = /actuator/health {
        return 404;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
~~~

将上面的站点配置保存为 /etc/nginx/sites-available/joysong-demo.conf，确认没有其他站点占用相同 default_server 后启用：

~~~bash
sudo chown root:root /etc/nginx/sites-available/joysong-demo.conf
sudo chmod 0644 /etc/nginx/sites-available/joysong-demo.conf
sudo ln -s /etc/nginx/sites-available/joysong-demo.conf /etc/nginx/sites-enabled/joysong-demo.conf
~~~

管理端使用独立版本目录。假设已把 dist 解压到 /tmp/joysong-admin-dist：

~~~bash
RELEASE_ID=20260901-001
sudo install -d -o root -g www-data -m 0750 /var/www/joysong-demo/releases/$RELEASE_ID
sudo cp -a /tmp/joysong-admin-dist/. /var/www/joysong-demo/releases/$RELEASE_ID/
sudo chown -R root:www-data /var/www/joysong-demo/releases/$RELEASE_ID
sudo chmod -R u=rwX,g=rX,o= /var/www/joysong-demo/releases/$RELEASE_ID
sudo ln -s /var/www/joysong-demo/releases/$RELEASE_ID /var/www/joysong-demo/current.next
sudo mv -Tf /var/www/joysong-demo/current.next /var/www/joysong-demo/current
~~~

如果数据库中仍存在旧的本地公共图片 URL，优先迁移到 OSS/CDN。确实需要只读 /images/ alias 时，应建立仅覆盖 uploads 的独立公共媒体组或精确 ACL，让 www-data 只能读取 uploads；不能把 www-data 加入 joysong-demo 组，因为该组还能读取服务端环境文件。绝不能为 /var/lib/joysong-demo/private 配置 Nginx location。

校验并加载：

~~~bash
sudo nginx -t
sudo systemctl reload nginx
curl --fail --head https://demo.example.com/
~~~

证书、DNS 和跳转稳定后，可按组织安全策略增加 HSTS。不要在证书和子域名尚未稳定时盲目开启 includeSubDomains。

## 13. 首次启动、管理员和演示数据

严格按以下顺序：

1. 确认连接的是空白独立 Demo 数据库。
2. 保留 ADMIN_PASSWORD，以 `demo` profile 首次启动。
3. Flyway 完成迁移，系统创建唯一管理员。
4. 使用管理员电话和密码登录管理后台。
5. 删除环境文件中的 ADMIN_PASSWORD，重启并再次登录。
6. 在管理后台“协议与隐私”中分别创建并发布用户协议、隐私政策；每份都必须包含 zh-CN 和 en-US 两个 locale。
7. 通过 /api/public/legal-documents/** 和 /legal/** 分别验证两个 locale，确认 App 登录前协议页不显示“暂未发布”。
8. 创建一次 RDS 手工备份，标记为 empty-with-admin-and-legal。
9. 在部署机执行 `pwsh -File scripts/demo-data.ps1 -Action Apply -JarPath <发布JAR绝对路径> -CatalogPath <catalog-v1.json绝对路径>`，临时注入 `ADMIN_PASSWORD` 和 `DEMO_ACCOUNT_PASSWORD`。
10. 执行同一脚本的 `Verify`，确认输出 `CATALOG_READY`、目录版本、SHA-256 和 `managed=378`。
11. 再创建一份 final-demo-catalog 备份。

协议表在空库迁移后没有默认内容，必须由管理员发布。协议正文属于正式对外文案，应由有权人员提供并审阅；当前 AI 翻译不会自动生成协议的 en-US 内容，未经审阅的机器翻译不能当作正式协议发布。

第 9、10 步现在使用同一个可执行入口，且目录静态解析发生在连接数据库之前。`Apply` 仅接受唯一管理员基线或完全一致的当前目录；中途失败回滚，重复执行完整目录时不写入。完整九步业务接口重放仍未实现，不能把数据库快照导入等同于接口重放验收。

远程发布不能依赖脚本的仓库内默认路径：必须显式传 JAR 和目录绝对路径，并人工核对发布清单中的目录版本与 SHA-256。当前已批准目录为 `2026.08.31.1`，SHA-256 为 `60e778ef88a4c9fd36a3758fe0400a3b10876ec23e3c1c603eeed8f8c12e6c3b`。首次 Apply 后立即从会话或环境文件删除 `ADMIN_PASSWORD`；常规 API 运行时同时移除 `DEMO_ACCOUNT_PASSWORD` 并保持 `DEMO_DATA_ENABLED=false`。

演示账号密码不写入本指南。最终交付时另生成一份不提交 Git 的加密测试账号清单，至少包含：

- 管理员后台地址、电话、初始密码；
- 各角色测试账号的电话、密码和用途；
- 专用于真实短信验收的授权手机号说明；
- APK 版本、API 域名、数据集版本和 SHA-256；
- 密码变更和环境下线日期。

该清单只发给指定验收人，Demo 结束后全部轮换或销毁。

## 14. App 安装包

1. 使用生产构建参数和 HTTPS API 域名生成 release APK。
2. 使用 apksigner 确认 APK 由项目 release keystore 签名，并核对证书指纹。
3. 在一台未连接开发电脑的 Android 真机安装。
4. 关闭电脑上的本地后端，确认 App 仍能访问云端。
5. 记录 APK SHA-256，并与交付清单一起发送。
6. 不通过公开网盘长期暴露包含测试入口的安装包；使用有访问控制和过期时间的交付渠道。

当前 Android applicationId 为 com.joysong.app，应用名称为“娇颜颂”，没有独立 Demo 包名或界面水印。如果验收设备已经安装同包名正式版，两者不能稳定并存；当前安装包也不能作为“带明显 Demo 标识的独立应用”交付。若主管要求与正式版并存，应另行增加 Demo applicationId、应用名称/图标和对应 Google OAuth 证书指纹，再重新构建。

如需 iOS 安装，还需要 Apple Developer 证书、Bundle ID、签名与 TestFlight 或受控企业分发流程，不由本指南中的 ECS 部署自动解决。

## 15. 验收清单

### 15.1 基础设施与安全

- [ ] demo.example.com 仅通过有效 HTTPS 访问，HTTP 自动跳转 HTTPS
- [ ] TLS 证书链、域名和有效期正确
- [ ] 8080、3306 不能从公网连接
- [ ] SSH 只允许固定运维来源
- [ ] Spring Boot 监听 127.0.0.1
- [ ] RDS 使用内网地址且白名单只有 Demo ECS
- [ ] ECS、RDS、OSS、RAM 与其他环境隔离
- [ ] 主机和 JVM 时区均为 Asia/Shanghai
- [ ] JAR、APK、日志和交付目录没有服务端密钥

### 15.2 后端与管理后台

- [ ] 本机 health 返回 UP
- [ ] 公网不暴露 actuator
- [ ] 管理后台首页、刷新深层路由、退出和重新登录正常
- [ ] 用户协议、隐私政策的 zh-CN/en-US API、HTML 页面和 App 登录前页面均可访问
- [ ] 非管理员账号访问 /api/admin/** 返回拒绝
- [ ] 管理员错误密码达到阈值后按预期锁定
- [ ] Nginx 和应用日志记录真实客户端 IP，而不是全部显示 127.0.0.1

### 15.3 数据

- [ ] 数据库为独立 Demo 库，不是开发库或生产库
- [ ] 首次管理员创建后已移除 ADMIN_PASSWORD
- [ ] 演示数据执行器和只读校验完成后，数量与批准设计一致
- [ ] 同一城市存在多家虚构机构
- [ ] 平台项目、机构项目、医生和顾问关系可浏览
- [ ] 所有业务展示数据为中文虚构数据
- [ ] 数据库重启后数据保持
- [ ] empty-with-admin-and-legal 与 final-demo-catalog 备份可见

### 15.4 OSS 图片

- [ ] 新上传公共图片写入 OSS，而不是 ECS 临时目录
- [ ] 返回 URL 使用 https://img-demo.example.com
- [ ] 手机蜂窝网络可直接打开图片
- [ ] OSS Bucket 保持私有并启用阻止公共访问
- [ ] CDN 私有回源正常
- [ ] 上传、删除、错误文件类型和超过 10 MiB 均按预期处理
- [ ] 私有身份/退款目录不能被 Nginx 或 CDN 访问

### 15.5 短信

- [ ] 企业资质、签名和中性验证码模板已审核
- [ ] 签名运营商实名报备已完成，通道实发验证成功
- [ ] 已通过阿里云确认本业务场景允许使用验证码短信
- [ ] 仅向经授权真实测试号码发送
- [ ] 手机实收验证码且 5 分钟内可用
- [ ] 60 秒内重复发送被拒绝
- [ ] Nginx IP 限流返回 429
- [ ] 阿里云控制台送达报告成功
- [ ] 验证码未出现在 API 响应和生产日志
- [ ] 短信费用和异常量告警已触发测试

### 15.6 AI、翻译与 SSE

- [ ] AI 咨询能通过公网创建会话
- [ ] 流式回答首包及时到达，不被 Nginx 缓冲到最后
- [ ] 连接持续时间超过普通请求时仍能完成
- [ ] 英文模式按当前实现完成一次真实翻译
- [ ] 翻译失败时有可理解提示，不只依赖 health
- [ ] AI 与翻译额度、错误率和费用有告警

### 15.7 APK

- [ ] 全新 Android 真机可安装 release APK
- [ ] APK 固定连接 HTTPS Demo 域名
- [ ] 断开开发电脑后浏览、登录、图片、AI 和翻译仍可用
- [ ] APK 内无数据库、OSS、短信、JWT 或 AI 服务端密钥
- [ ] apksigner 校验通过，签名证书指纹与交付记录一致
- [ ] APK SHA-256 与交付清单一致

### 15.8 当前必须标记为未通过的项

- [ ] OSS 新 Bucket 上传/删除：HTTPS、Signature V4、region 改造和新 Bucket 集成测试完成前不得勾选
- [ ] 一键主管快照：隔离 MySQL Apply/Verify/重复 Apply 通过且远程导入留证后才能勾选；九步接口重放仍未实现
- [ ] 模拟订单支付：快速 Demo 明确不包含该能力，若以后纳入必须另行设计和验收

## 16. 发布、回滚与日常运维

### 16.1 发布

1. 记录 Git 提交、JAR/Admin/APK SHA-256。
2. 创建 RDS 手工备份。
3. 把 JAR 和管理端 dist 上传到各自的新版本目录，不覆盖 current。
4. 原子切换 /opt/joysong-demo/current 后，执行 systemctl restart joysong-demo。
5. 检查新进程、提交版本、迁移日志和 127.0.0.1 health；失败时切回旧后端软链接并再次重启服务。
6. 后端通过后，原子切换 /var/www/joysong-demo/current，执行 nginx -t 和 systemctl reload nginx。
7. 执行第 15 节冒烟。
8. 只在全部必需项通过后发送 APK 和账号清单。

### 16.2 应用回滚

- 保留至少一个已验证 JAR 和管理端 dist。
- 应用回滚需切回上一版本软链接；后端切换后必须重启 joysong-demo，管理端切换后校验并重载 Nginx。
- 如果新版本包含不可向后兼容迁移，必须按预先验证的数据库恢复方案处理，不能直接运行逆向 SQL。

### 16.3 数据恢复

- 将备份恢复到新 RDS 实例。
- 打印并复核新主机和数据库名。
- 用临时服务实例执行 health、管理员登录和数据只读校验。
- 验证通过后切换正式 Demo 的 DB_URL。
- 原库保留到观察期结束，确认后再按阿里云回收流程处理。

### 16.4 密钥轮换与下线

- OSS、短信、数据库、JWT、AI 和翻译密钥分别轮换。
- 先添加新密钥、验证，再停用旧密钥。
- Demo 结束后暂停短信发送、下线域名、回收 ECS/RDS/CDN，并确认自动备份和快照是否仍产生费用。
- 删除测试人员真实手机号和加密账号交付文件。
- 保留不含秘密的部署记录和验收结果。

## 17. 已知限制与后续代码任务

以下不应通过运维配置“绕过去”：

1. 部署 OSS 前改造 OssConfig：显式使用 HTTPS、Signature V4 和 Bucket region，并在新私有 Bucket 上做集成测试。
2. 在独立 RDS 上执行并留存主管快照 Apply/Verify 证据；另行实现真实接口重放工具。
3. 如需模拟支付，在现有安全 Demo 门禁之外另做无真实资金能力的专门设计；禁止 dev 公网运行。
4. OSS 与短信改用 ECS 实例 RAM 角色或默认凭据链，删除长期 AccessKey。
5. 将验证码、IP 限流、管理员防爆破状态迁移到 Redis 后，才能考虑多实例。
6. 为短信增加 BizId 持久化、送达回执或查询能力；当前只能在阿里云控制台核对。
7. 如需私有文件上云，实现私有 OSS 与短时授权下载，不能使用公共 CDN URL。
8. 增加自动化制品扫描，阻止 application-dev.yml、环境文件和密钥进入 JAR/APK。
9. 完成部署相关代码变更后，提醒更新 design/ 下的部署与外部服务关系 UML，由维护者生成图片。

## 18. 阿里云官方参考

- ECS 安全组：[安全组典型应用](https://help.aliyun.com/en/ecs/user-guide/security-groups-for-different-use-cases)、[开始使用安全组](https://help.aliyun.com/en/ecs/user-guide/start-using-security-groups)
- ECS 快照：[创建自动快照策略](https://help.aliyun.com/zh/ecs/user-guide/create-policy)
- RDS 网络隔离：[连接与网络](https://help.aliyun.com/en/rds/apsaradb-rds-for-mysql/connections-and-networks/)、[网络隔离](https://help.aliyun.com/en/rds/apsaradb-rds-for-mysql/network-isolation)
- RDS 权限边界：[账号权限](https://help.aliyun.com/en/rds/apsaradb-rds-for-mysql/account-permissions)、[关联安全组](https://help.aliyun.com/zh/rds/apsaradb-rds-for-mysql/set-security-group)
- RDS 备份：[备份指南](https://help.aliyun.com/en/rds/apsaradb-rds-for-mysql/backup-guide)、[全量备份](https://help.aliyun.com/en/rds/apsaradb-rds-for-mysql/full-backup)
- RDS 恢复：[恢复全量数据到新实例](https://help.aliyun.com/zh/rds/apsaradb-rds-for-mysql/restore-full-data-of-an-apsaradb-rds-for-mysql-instance)
- RDS SSL：[SSL 连接 RDS MySQL](https://help.aliyun.com/en/rds/apsaradb-rds-for-mysql/ssl-connect-to-the-rds-mysql-database)
- DNS：[云解析 DNS 入门](https://help.aliyun.com/en/dns/beginner-s-guide)、[添加解析记录](https://help.aliyun.com/en/dns/pubz-add-parsing-record)
- ICP 备案：[备案申请概览](https://help.aliyun.com/zh/icp-filing/basic-icp-service/user-guide/icp-filing-application-overview)、[备案服务器检查](https://help.aliyun.com/zh/icp-filing/basic-icp-service/user-guide/icp-filing-server-access-information-check)
- App 与公安联网备案：[App 备案快速入门](https://help.aliyun.com/zh/icp-filing/basic-icp-service/getting-started/quick-sta-rt-for-icp-filing-for-personal-app)、[公安联网备案快速入门](https://help.aliyun.com/zh/icp-filing/basic-icp-service/quick-start-for-public-security-network-filing-for-personal-websites)
- Nginx 证书：[在 Nginx 安装 SSL 证书](https://help.aliyun.com/en/ssl-certificate/install-ssl-certificates-on-nginx-servers-or-tengine-servers)
- OSS 网络与 Endpoint：[访问和网络概览](https://help.aliyun.com/en/oss/user-guide/access-and-network-overview)、[地域和 Endpoint](https://help.aliyun.com/en/oss/user-guide/regions-and-endpoints)
- OSS Java SDK 与 V4：[Java SDK V1 配置](https://help.aliyun.com/en/oss/developer-reference/oss-java-sdk/)、[从 V1 签名升级到 V4](https://help.aliyun.com/en/oss/developer-reference/guidelines-for-upgrading-v1-signatures-to-v4-signatures)
- OSS 权限：[阻止公共访问](https://help.aliyun.com/en/oss/user-guide/block-public-access)、[RAM Policy](https://help.aliyun.com/en/oss/user-guide/ram-policy/)
- OSS HTTPS：[HTTPS 访问](https://help.aliyun.com/en/oss/user-guide/access-oss-by-https-protocol)
- CDN 配置：[添加加速域名](https://help.aliyun.com/zh/cdn/add-a-domain-name)、[CDN 域名备案](https://help.aliyun.com/zh/icp-filing/basic-icp-service/product-overview/use-alibaba-cloud-cdn)、[回源协议](https://help.aliyun.com/en/cdn/user-guide/configure-the-origin-protocol-policy)、[私有 OSS 回源授权](https://help.aliyun.com/en/cdn/user-guide/grant-alibaba-cloud-cdn-access-permissions-on-private-oss-buckets)
- 短信开通：[短信服务快速入门](https://help.aliyun.com/en/sms/getting-started/get-started-with-sms)
- 短信资质与签名：[资质申请说明](https://help.aliyun.com/zh/sms/user-guide/qualification-application-description)、[创建短信签名](https://help.aliyun.com/zh/sms/user-guide/create-signatures/)
- 短信 API 与权限：[SendSms](https://help.aliyun.com/en/sms/developer-reference/api-dysmsapi-2017-05-25-sendsms)、[RAM 鉴权](https://help.aliyun.com/en/sms/developer-reference/api-dysmsapi-2017-05-25-ram)、[自定义权限策略](https://help.aliyun.com/zh/sms/custom-permission-policy-reference)
- 短信规则与防刷：[发送规则](https://help.aliyun.com/en/sms/user-guide/message-rules)、[验证码盗刷防护](https://help.aliyun.com/en/sms/user-guide/verification-code-scams-and-message-flooding-1)
- 短信频控、总量和测试：[发送频率与白名单](https://help.aliyun.com/zh/sms/user-guide/configure-delivery-frequency-and-whitelist/)、[发送量预警与限额](https://help.aliyun.com/zh/sms/user-guide/configure-alerting-for-messages)、[发送测试短信](https://help.aliyun.com/zh/sms/user-guide/send-test-messages-1/)
- 验证码模板规范：[验证码模板规范](https://help.aliyun.com/zh/sms/user-guide/verification-code-template-specifications/)
