# 医生与顾问机构选择页设计

## 1. 目标与范围

医生或顾问在专业管理中心申请绑定机构时，不再从一次性加载的下拉框中选择机构，而是进入“全部机构”选择页，通过搜索和分页定位机构，点击机构卡片后把机构 `id` 和 `name` 返回申请页。

本切片只调整 Flutter 交互与依赖装配，复用发现页已有公开机构接口，不新增或修改后端接口。医生离开机构仍只能从本人已绑定机构中本地选择，避免把无关机构带入 `LEAVE` 申请。

## 2. 当前问题

- `InstitutionMembershipRequestsPage` 通过 `listInstitutionOptions()` 一次加载机构并使用下拉框；机构数量增长后难以浏览和搜索。
- 页面根据 `activeRoles` 中是否包含 `DOCTOR` 推断 `requestType`。同时拥有医生和顾问身份时，从顾问入口进入也会误投为 `DOCTOR`。
- `InstitutionRelationshipsPage` 的医生 `JOIN` 同样使用全量下拉；`LEAVE` 则适合继续使用本人已绑定机构的本地集合。
- 发现模块已经具有机构列表所需的模型、网络访问、搜索、分页和卡片，重复实现会产生两套行为。

## 3. 采用方案

新增轻量 `InstitutionPickerPage`，放在 discover presentation 层，并直接复用：

- `DiscoverRepository.loadPage(type: DiscoverContentType.institution, offset, limit, query)`；
- `DiscoverController` 的首屏加载、搜索条件保存、`loadMore()` 和去重逻辑；
- `DiscoverContentCard` 的机构卡片视觉；
- 发现页现有加载、空态、失败重试及列表触底模式。

选择页只展示机构类型，不展示发现页的综合 tab、筛选面板或详情导航。点击卡片直接 `Navigator.pop` 返回不可变的 `InstitutionPickerSelection(id, name)`，不得先打开机构详情。搜索输入沿用 350ms 防抖；列表触底调用现有控制器分页。

`DiscoverRepository` 由 `AppShell -> ProfilePage -> ManagementCenterPage -> _ManagementCapabilities` 注入到申请页面和医生关系页面，不在页面内部创建第二个 API client 或 repository。未注入时相关入口应保持不可用或显示明确失败态，不回退到固定数量下拉。

### 文件触及面

- `discover_page.dart`：只作为现有搜索、状态与触底交互的参考；不把整页或综合 tab 嵌入 picker。
- `discover_controller.dart`、`discover_repository.dart`：直接复用 `load(query:)`、`loadMore()` 与 `loadPage(...)`，原则上不改公开契约。
- `discover_content_card.dart`：复用机构卡片视觉，并由 picker 接管点击返回语义。
- 新增 `institution_picker_page.dart`：承载仅机构的搜索分页列表及 `InstitutionPickerSelection`。
- `professional_request_pages.dart`：接收显式 `requestType` 和 discover repository，以 picker 替换绑定申请下拉。
- `institution_relationships_page.dart`：医生 `JOIN` 使用 picker，`LEAVE` 保留本地绑定项。
- `identity_pages.dart`：医生/顾问入口显式传类型，并继续向关系页传 discover repository。
- `profile_page.dart`、`app_shell.dart`：沿现有对象装配链透传同一个 `DiscoverRepository`。

## 4. 角色与提交边界

`InstitutionMembershipRequestsPage` 新增必填构造参数 `requestType`，只接受 `DOCTOR` 或 `CONSULTANT`。医生菜单入口显式传 `DOCTOR`，顾问菜单入口显式传 `CONSULTANT`；提交时原样传给 `submitInstitutionMembershipRequest`，不再从 `activeRoles` 的顺序或集合内容推断。

医生关系页的行为分离如下：

- `JOIN`：打开 `InstitutionPickerPage`，选中后保存返回的 `id/name` 并显示在表单中；
- `LEAVE`：保留本地下拉，只列出 `ManagementContext.doctorInstitutionIds` 对应的已绑定机构；
- 切换 `JOIN/LEAVE` 时清空上一次选择，防止跨动作复用错误 ID。

顾问与医生共享同一个 picker，但请求类型和提交 API 仍由各自页面契约决定。审核页、机构归属只读页以及法人审核流程不进入选择模式。

## 5. 数据流

1. 用户从医生或顾问菜单进入申请页，入口同时传入显式 `requestType`。
2. 用户点击“选择机构”，push `InstitutionPickerPage`。
3. picker 以 `DiscoverContentType.institution` 加载 `/discover/institutions?offset&limit&query`。
4. 搜索更新 query 并从 offset 0 重载；触底按当前 query 加载下一页。
5. 用户点击机构卡片，picker pop `InstitutionPickerSelection(id, name)`，不请求详情。
6. 申请页展示机构名称，并使用机构 ID 与显式请求类型提交。

## 6. 错误与状态

- picker 首屏失败显示重试；分页失败保留已加载项并允许再次触发。
- 空结果展示双语空态，不把空结果误判为机构接口不可用。
- 返回不选择时保留进入 picker 前的选择；成功选择时同时替换 id/name。
- 提交期间禁用选择和提交按钮，避免选择发生变化后重复提交。
- 页面销毁时释放搜索控制器、防抖 timer 和 `DiscoverController`。

## 7. 测试边界

仅新增 3 个核心测试：

1. picker 搜索把 trim 后 query 转发给 `DiscoverRepository.loadPage`；
2. 点击机构卡片直接 pop 返回 id/name，且不调用 `loadDetail`；
3. 同时具有医生和顾问身份时，两个入口分别提交显式 `DOCTOR` 与 `CONSULTANT`。

现有 `discover_models_controller_test.dart` 已覆盖分页、offset 递增和去重，不重复新增分页测试；现有医生关系页 `LEAVE` 测试继续证明离开机构只使用已绑定本地选择。

## 8. 非目标

- 不新增后端机构搜索接口或修改 `/discover/institutions`。
- 不改变机构绑定、离开、审核的后端权限与状态机。
- 不把机构详情页嵌入 picker，也不复制发现页 repository/controller/card。
- 不把机构项目发布页面等其他下拉框纳入本切片。
