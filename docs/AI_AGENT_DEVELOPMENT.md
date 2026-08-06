# JoySong AI Agent 开发文档

## 1. 功能目标

AI Agent 面向医美咨询场景，优先使用平台数据库中的机构、医生、项目和机构项目资料回答问题。它支持多轮上下文、混合意图路由、模糊关键词搜索、结构化对比报告、详情卡片、规划入口和风险筛查入口。

回答正文只概括最相关的信息，数据库明细由卡片或报告承载。平台已有资料时不使用外部搜索覆盖内部事实。

## 2. 总体调用链

PlantUML 源码见 [`AI_AGENT_SEQUENCE.puml`](./AI_AGENT_SEQUENCE.puml)。

```text
用户问题
→ 保存消息并读取短历史
→ 解析和更新上下文关键词
→ 本地评估意图、查询目标和路由可靠度
→ 仅对模糊或复杂问题调用轻量 LLM 解析
→ 统一发现搜索
→ 必要时直接查询机构项目关系
→ 按当前目标类型筛选
→ 生成最终数据库证据
→ 调用大模型生成简短回答
→ 返回回答、路由动作、卡片或对比报告
```

### 2.1 混合路由

`AgentIntentRouter` 一次返回：

```text
intent + queryTarget + searchCatalog + nextAction
```

意图包括 `GENERAL_CHAT`、`CATALOG_QA`、`COMPARISON`、`PLANNING`、`DETAIL_SUMMARY` 和 `SAFETY_SCREENING`。查询目标包括机构、医生、项目和机构项目。

明确、无冲突的问题走本地快路径；目标缺失、意图冲突、未解析指代或多约束问题才调用轻量 LLM，并要求只返回受控 JSON。非法 JSON、非法枚举或超时会回退本地决策。安全意图只能升级，不能被模型降级。

## 3. 多轮上下文

### 3.1 可识别槽位

- 城市
- 项目名称
- 项目类别
- 项目标签
- 机构名称
- 医生名称
- 机构与项目组合
- 医美主题词
- 返回类型：机构、医生、项目、机构项目

### 3.2 更新规则

1. 当前问题中的明确新值优先于历史值。
2. 完整的新问题开启新主题，不继承无关条件。
3. “那……呢”“同样”“换成”“how about”等承接表达保留未被替换的槽位。
4. 当前问题没有返回类型时，承接式追问可继承上一轮返回类型。
5. 最近 4 条历史用户消息参与补全；本轮消息必须先从历史集合中排除。

示例：

```text
上海有哪些热玛吉医生？
那深圳的呢？
```

第二轮内部查询为：

```text
深圳 + 热玛吉 + 医生
```

如果第二轮改为完整问题“深圳有哪些机构？”，则开启新主题，关键词只保留“深圳”。

## 4. 数据库搜索

`DiscoverSearchService` 是发现页和 Agent 共用的内部搜索能力。发现页原有接口行为不变，Agent 通过服务层复用关键词、城市、项目、机构和医生匹配。

### 4.1 机构项目直接查询

机构项目不能只依赖文本搜索。Agent 在目标为 `INSTITUTION_PROJECT` 时执行关系查询：

1. 用项目名称、类别、标签或领域词找到项目 ID。
2. 用城市找到机构 ID。
3. 在 `institution_projects` 中查询有效关联。
4. 读取机构项目的有效详情、机构和项目详情组成卡片或报告。

该路径可正确处理“热玛吉抗衰”项目与“热玛吉”简称之间的匹配。

#### 4.1.1 独立详情合并规则

Agent 查询到 `institution_projects` 后，必须通过 `InstitutionProjectDetailResolver` 取得有效详情，不能直接只读取关联 `projects` 表。`name`、`category`、`description`、`rating`、`reviewCount`、`tags`、`slogan`、`detailContent` 优先使用机构项目的已配置值；字段为 `NULL` 或空白时回退项目模板。封面和图集同样使用有效值。

因此，AI 正文、详情卡片、对比报告和总结均以同一份有效详情为准，避免“项目模板名称/说明”与机构项目实际展示内容不一致，也避免未配置独立详情时查不到资料。

### 4.1.2 医生多机构关联

医生的出诊机构以 `doctor_institutions` 为准，不再只依赖 `doctors.institution_id`。按机构名或城市检索医生时，Agent 匹配任一有效绑定机构；医生卡片继续使用主机构作为默认展示。机构项目场景仍必须同时命中 `doctor_projects.institution_project_id`，不能因普通机构绑定而误认为该医生可提供该项目。

### 4.2 无结果规则

- 明确项目、医生或机构不存在时，不使用无关热门数据兜底。
- 不编造机构名、医生名、价格或资质。
- 无卡片和报告时，App 不展示空结构化区域。

## 5. 回答生成

最终数据库证据放在大模型生成前的最后一条系统消息中，优先级高于旧对话。正文和卡片必须使用同一份最终结果。

用户侧回答避免内部术语：

| 内部表达 | 用户侧表达 |
| --- | --- |
| 命中、命中集 | 平台上查到、目前可以看到 |
| 数据库记录 | 平台资料 |
| 字段未填写 | 资料中暂未注明 |
| 检索结果 | 平台信息 |

默认回答为 60–180 个中文字、1–3 个短段。简单问题不强行添加“结论”等标题。

## 6. App 结构化展示

`ChatTurnResponse` 包含：

```text
message       AI 消息
catalogItems  普通数据库结果卡片，最多 4 条
catalogReport 对比或总结报告
intent        最终意图
queryTarget   数据库查询目标
nextAction    App结构化下一步动作
```

`nextAction` 可为 `NONE`、`SHOW_CATALOG`、`START_PLANNING` 或 `COMPLETE_SAFETY_SCREENING`。规划和风险只在特定意图下展示结构化入口；用户填写并确认档案后，App 才调用正式的安全评估和规划服务。自由聊天文本不会直接生成正式医学计划。

普通搜索卡片紧跟对应 AI 气泡，支持跳转：

- `INSTITUTION` → 机构详情
- `DOCTOR` → 医生详情
- `PROJECT` → 项目详情
- `INSTITUTION_PROJECT` → 机构项目详情

对比报告存在时不重复展示普通卡片。删除 AI 消息时同步清理其卡片和报告。

## 7. 测试观测

测试阶段可使用 `agent_tool_audits` 检查调用链。它是旁路测试日志，保存失败不能影响回答，也不参与意图、搜索、安全或规划决策。V21 将 `detected_cities` 重命名为 `detected_keywords`，并将长度扩大至 1000。

主要字段：

- `request_summary`
- `intent`
- `database_search`
- `detected_keywords`
- `detected_concerns`
- `matched_entity_ids`
- `llm_called`
- `model_name`
- `gateway_url`
- `database_duration_ms`
- `llm_duration_ms`
- `total_duration_ms`
- `input_tokens` / `output_tokens`
- `fallback_used`
- `error_summary`

关键词保存前会去空、去重并移除整句碎片。示例：

```text
上海,热玛吉
```

检查 SQL：

```sql
SELECT created_at, request_summary, intent, database_search,
       detected_keywords, matched_entity_ids, result_status,
       database_duration_ms, llm_duration_ms, total_duration_ms,
       http_status, fallback_used, error_summary
FROM joysong.agent_tool_audits
ORDER BY created_at DESC
LIMIT 20;
```

## 8. 数据库迁移

- V18：Agent 规划、档案、风险与审计基础表
- V19：聊天表重命名为 `agent_sessions`、`agent_messages`
- V20：扩充 `agent_tool_audits`
- V21：`detected_cities` → `detected_keywords`
- V22：机构项目新增可选的独立详情覆盖字段；Agent 通过详情解析器读取其有效值
- V23：新增 `doctor_institutions`，医生可绑定多家机构，按城市和机构检索使用全部有效关联

生产环境禁止删除 Flyway 历史记录。服务启动时由 Flyway 顺序执行新增迁移。

## 9. 配置

```text
OPENAI_API_KEY
OPENAI_BASE_URL=https://www.fastaitoken.com/v1
OPENAI_MODEL=gpt-4.1-mini
OPENAI_STREAM_ENABLED=false
OPENAI_INTENT_PARSER_ENABLED=true
DB_PASSWORD
JWT_SECRET
```

密钥只保存在后端环境变量中，不写入 App 或 Git。

轻量意图解析仅在本地可靠度不足时调用，最多输出 180 tokens；连接超时为 3 秒、读取超时为 8 秒，失败后立即回退。最终回答使用独立的长超时客户端。

## 10. 验收用例

### 普通查询

```text
上海有哪些机构？
上海有哪些热玛吉医生？
有哪些热玛吉机构项目？
```

### 承接查询

```text
有哪些热玛吉机构项目？
那上海的呢？
```

预期关键词为 `上海,热玛吉`，返回类型为 `INSTITUTION_PROJECT`，并展示详情卡片。

### 新主题

```text
上海有哪些热玛吉医生？
深圳有哪些机构？
```

第二轮关键词应更新为 `深圳`，不得继承上海或热玛吉。

### 对比

```text
有热玛吉项目吗？
对比机构项目
```

预期意图为 `COMPARISON`，只生成机构项目对比报告。

### 构建检查

```powershell
cd joysong-server
.\gradlew.bat test

cd ..\joysong-app
.\gradlew.bat :app:compileDebugKotlin
```

完整测试方法、API 回归脚本和模拟网关用法见 [`AI_AGENT_TESTING.md`](./AI_AGENT_TESTING.md)。

## 11. 已知边界

- “第二家”“前两个”“它们哪个更好”等序号和复数指代，需要额外持久化上一轮结果 ID。
- 平台资料不等同于医疗诊断，最终适用性仍需由具备资质的医生面诊确认。
- 网关超时不会改变数据库搜索结果，但可能触发简短降级回答。
- 普通搜索卡片目前按消息保存在 App 内存映射中；历史报告可重建，普通历史卡片持久恢复仍需消息结构化元数据支持。
