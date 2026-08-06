# JoySong AI Agent 测试指南

测试分为三组：快速单元测试、真实后端 API 回归、模拟 LLM 异常测试。建议提交前运行前两组，修改网关或路由代码时再运行第三组。

## 1. 快速单元测试

在 PowerShell 中执行：

```powershell
cd D:\code\kotlin\joysong\joysong-server
.\gradlew.bat test --tests com.joysong.server.agent.service.AgentIntentRouterTest
```

覆盖范围：

- 中英文普通聊天、机构、医生、项目、机构项目和对比意图
- 高可靠度本地快路径
- 模糊、多约束问题的 LLM 解析门槛
- 孕期、哺乳、过敏、感染、服药、瘢痕和效果保证风险
- 风险优先级、禁止目录搜索和风险筛查动作

测试报告：

```text
joysong-server/build/reports/tests/test/index.html
```

运行服务端全部测试：

```powershell
.\gradlew.bat test
```

## 2. 启动后端

至少配置数据库、JWT 和模型网关环境变量：

```powershell
$env:DB_PASSWORD="数据库密码"
$env:JWT_SECRET="不少于256位安全强度的密钥"
$env:OPENAI_API_KEY="模型网关Key"
$env:OPENAI_BASE_URL="https://www.fastaitoken.com/v1"
$env:OPENAI_MODEL="gpt-4.1-mini"
$env:OPENAI_INTENT_PARSER_ENABLED="true"

cd D:\code\kotlin\joysong\joysong-server
.\gradlew.bat bootRun
```

## 3. API 全量回归

推荐直接传入测试账号的 JWT：

```powershell
cd D:\code\kotlin\joysong\joysong-server
.\scripts\ai-agent-regression.ps1 -Token "JWT_TOKEN"
```

也可以使用测试账号自动登录：

```powershell
$env:JOYSONG_TEST_PHONE="测试手机号"
$env:JOYSONG_TEST_PASSWORD="测试密码"
.\scripts\ai-agent-regression.ps1
```

指定其他后端地址：

```powershell
.\scripts\ai-agent-regression.ps1 `
  -BaseUrl "http://192.168.1.10:8080" `
  -Token "JWT_TOKEN"
```

默认模式验证接口结构、路由、多轮上下文、安全优先级和消息持久化，不强制要求测试数据库一定存在热玛吉数据。

如果测试库已经准备好上海、深圳、热玛吉、医生和机构项目关系数据，启用严格目录断言：

```powershell
.\scripts\ai-agent-regression.ps1 -Token "JWT_TOKEN" -StrictCatalog
```

严格模式额外检查：

- 目录问题必须返回卡片或报告
- 对比报告只能包含一种实体类型
- 城市切换后不能继续展示旧城市卡片
- 机构项目应返回真实关联数据

脚本任一用例失败都会以退出码 `1` 结束，适合接入 CI。

## 4. 模拟 LLM 异常

打开第一个 PowerShell 窗口：

```powershell
cd D:\code\kotlin\joysong\joysong-server
.\scripts\mock-llm-gateway.ps1 -Scenario valid
```

第二个窗口使用模拟网关启动后端：

```powershell
$env:OPENAI_API_KEY="test-key"
$env:OPENAI_BASE_URL="http://127.0.0.1:18080/v1"
$env:OPENAI_INTENT_PARSER_ENABLED="true"
.\gradlew.bat bootRun
```

第三个窗口运行 API 回归：

```powershell
.\scripts\ai-agent-regression.ps1 -Token "JWT_TOKEN"
```

支持的模拟场景：

```powershell
.\scripts\mock-llm-gateway.ps1 -Scenario valid
.\scripts\mock-llm-gateway.ps1 -Scenario invalid-json
.\scripts\mock-llm-gateway.ps1 -Scenario illegal-enum
.\scripts\mock-llm-gateway.ps1 -Scenario timeout
.\scripts\mock-llm-gateway.ps1 -Scenario safety-downgrade
```

预期行为：

| 场景 | 预期结果 |
|---|---|
| `valid` | 模糊问题采用结构化解析结果 |
| `invalid-json` | 放弃解析并回退本地路由 |
| `illegal-enum` | 拒绝非法枚举并回退 |
| `timeout` | 约8秒后回退，不出现500/502 |
| `safety-downgrade` | 本地明确风险不调用解析器，不能被降级 |

切换场景前停止并重新启动模拟网关。

## 5. 测试数据库建议数据

严格模式建议至少准备：

- 上海、深圳各2家机构
- 热玛吉、玻尿酸、水光针等项目
- 每个项目至少2条机构项目及价格
- 上海、深圳各2名医生
- 正确的 `doctor_projects` 关系
- 1名没有热玛吉关系的医生用于反向过滤
- 1个不存在的项目或医生名称用于无结果测试

不要在共享生产数据库运行会清理会话的数据测试。当前回归脚本只创建测试会话和消息，不删除业务目录数据。

## 6. 验收重点

- 明确问题只调用最终回答 LLM，不额外解析意图
- 模糊问题最多调用一次解析 LLM 和一次回答 LLM
- 解析超时最多8秒后回退
- 安全问题不返回机构、项目卡片或对比报告
- 对比仅包含同一种实体
- 第二轮正确继承项目和目标，同时替换新城市
- 新主题能够清除旧目标
- AI 回复与历史消息成功持久化
- 中文与英文路由结果一致

