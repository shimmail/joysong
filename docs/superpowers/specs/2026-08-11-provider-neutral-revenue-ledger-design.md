# 渠道无关的分账与钱包账本设计

## 1. 背景与目标

娇颜颂计划由中国大陆公司作为 Merchant of Record，主要面向境外用户以 USD 收款，接受境外银行卡、Apple Pay 等支付方式。首选支付及出款服务商为 Airwallex，但大陆主体的行业准入、医疗服务款项性质和中国大陆出款路径仍需业务、法务、财税及 Airwallex 书面确认。

本阶段不等待渠道确认，先实现与 Airwallex 无关的资金领域核心：不可变结算快照、钱包账本、幂等处理、退款冲正及内部对账。后续支付和出款渠道只能通过 adapter 接入，不得改变核心账务语义。

## 2. 范围

### 2.1 本阶段包含

- 根据订单净实收生成唯一、不可变的四方结算快照。
- 为平台、机构、医生和医美顾问建立按币种隔离的钱包。
- 使用只追加的不可变账本维护待结算、可用和冻结余额。
- 将到期结算从待结算余额幂等释放到可用余额。
- 对已成功退款执行幂等反向记账。
- 校验订单、结算、账本和钱包余额的一致性并记录异常。
- 提供后续 Airwallex Payment、FX 和 Transfer 对账所需的扩展边界。

### 2.2 本阶段不包含

- Airwallex API、webhook、访问令牌和密钥配置。
- beneficiary、KYC、银行账户采集与验证。
- 提现申请、人工审核、真实出款及出款失败恢复。
- USD 到 CNY 的报价、换汇或汇率损益分配。
- 医生和顾问由平台直接收款还是经机构结算的最终业务决策。
- 钱包页面、提现页面和财务人工调账后台。

## 3. 核心原则

1. **资金事实只追加**：已创建的结算分配和账本流水不得修改或删除；纠错通过反向流水完成。
2. **金额以最小货币单位计算**：核心计算使用 `Long` minor units，并根据 ISO 4217 币种精度转换；不得使用 `Double`。
3. **历史配置不追溯**：结算记录保存订单参与方、比例、金额和币种快照，后续配置变化不影响历史记录。
4. **渠道结果不是账本**：Airwallex 等渠道只负责收付款，内部账本是业务余额的唯一事实来源。
5. **每项资金动作幂等**：业务 operation key 和数据库唯一约束共同防止重复结算、释放和冲正。
6. **异常不静默修复**：对账发现差异时创建异常记录，由后续运营流程处理，不直接改写余额。

## 4. 领域模型

### 4.1 Settlement

一个订单最多有一条 `Settlement`，数据库对 `order_id` 建立唯一约束。结算记录包含：

- 订单 ID、币种、净实收 minor amount；
- 平台、机构、医生、顾问的身份快照；
- 四方比例快照；
- 四方 allocation minor amount；
- 结算到期时间；
- `CALCULATED`、`PENDING`、`AVAILABLE`、`PARTIALLY_REVERSED`、`REVERSED`、`EXCEPTION` 状态；
- 创建、释放和冲正的版本及时间戳。

结算基数定义为订单中渠道已确认成功的支付总额减去渠道已确认成功的退款总额。优惠已经反映在实际支付额中，不重复扣减。尚处于处理中或结果未知的支付、退款不得进入结算基数。

### 4.2 SettlementAllocation

每条结算产生四条 allocation：`PLATFORM`、`INSTITUTION`、`DOCTOR`、`CONSULTANT`。每条记录保存 owner ID、owner name、rate、amount minor 和状态。

金额分配按稳定顺序执行：先对平台、机构、顾问份额向最小货币单位舍入，医生获得剩余金额。必须满足：

```text
platform + institution + consultant + doctor = net paid
```

所有份额不得为负。主体快照缺失、比例不合法或净实收不为正时拒绝创建结算，并创建对账异常。

### 4.3 Wallet

钱包唯一键为：

```text
owner_type + owner_id + currency
```

钱包维护三个投影余额：

- `pending_minor`：等待结算期结束；
- `available_minor`：可以进入未来提现流程；
- `frozen_minor`：已被未来提现或风险流程占用。

钱包余额是账本的高效投影，不是独立资金事实。任何余额变化必须与账本流水在同一数据库事务内完成。

### 4.4 WalletLedgerEntry

账本流水只追加，包含：

- wallet ID、币种、金额和方向；
- `SETTLEMENT_CREDIT`、`RELEASE`、`HOLD`、`UNHOLD`、`REVERSAL`、`ADJUSTMENT` 类型；
- pending、available、frozen 的变动值；
- settlement、allocation、refund 等来源引用；
- 全局唯一 operation key；
- 创建时间、操作者类型和审计元数据。

常规业务不得直接创建 `ADJUSTMENT`；该类型仅为后续受控财务流程预留。

### 4.5 ReconciliationIssue

对账异常至少包含：检查类型、业务对象、期望金额、实际金额、币种、严重级别、状态、首次及最近发现时间。相同业务对象和检查类型在未关闭期间只能存在一条活动异常。

## 5. 业务流程

### 5.1 创建结算

1. 订单完成并进入待结算阶段。
2. 锁定订单并检查是否已有结算。
3. 读取已确认成功的支付和退款，计算净实收。
4. 从订单快照读取四方主体，从订单完成时的有效配置冻结比例。
5. 按 minor units 计算四条 allocation。
6. 创建结算和 allocation。
7. 按固定 owner key 顺序锁定或创建钱包。
8. 为各 allocation 写入 `SETTLEMENT_CREDIT`，增加 `pending`。
9. 提交事务后，订单保持 `PENDING_SETTLEMENT`。

重复请求使用 `settlement:create:<orderId>`，返回已有结果，不重复入账。

### 5.2 到期释放

1. 调度任务分页读取到期且仍为 `PENDING` 的结算。
2. 对单条结算加锁，再按固定顺序锁定相关钱包。
3. 为每条 allocation 写入 `RELEASE`，等额减少 pending、增加 available。
4. 所有 allocation 成功后，将结算标记为 `AVAILABLE`，订单标记为 `SETTLED`。

每条释放流水使用 `settlement:release:<settlementId>:<allocationId>`。任何一步失败都回滚该结算的整次释放，其他结算可继续处理。

### 5.3 退款冲正

退款模块只在渠道确认退款成功后调用账本冲正：

- 尚未释放：按退款占净实收的比例冲减各 allocation 的 pending；
- 已释放：冲减 available；
- 已存在 frozen 资金：本阶段不自动动用 frozen，记录 `RECOVERY_REQUIRED` 异常；
- available 不足：扣至零，未覆盖金额记录为待追偿，不允许钱包产生可提现负余额。

冲正使用已成功退款项作为最小幂等单元，operation key 为 `refund:reverse:<refundItemId>:<allocationId>`。累计冲正金额不得超过对应 allocation。全额冲正后结算为 `REVERSED`，部分冲正为 `PARTIALLY_REVERSED`。

舍入采用累计目标法：根据订单累计成功退款额计算每个 allocation 应累计冲正的目标值，再减去已经冲正的金额，确保多次部分退款后的最终总额守恒。

## 6. 并发与事务

- 创建结算依靠 `settlements.order_id` 唯一约束，不使用单纯的先查后插保证唯一性。
- operation key 建立唯一约束，重复业务操作读取已有流水并视为成功。
- 钱包更新采用悲观锁或带版本号的条件更新，失败时返回可重试的并发错误。
- 多钱包事务按 `currency, owner_type, owner_id` 排序后依次加锁。
- 订单、结算、allocation、钱包和账本的单次领域操作必须位于同一数据库事务。
- 调度任务使用小批次、逐结算事务，避免一个异常阻断全部到期任务。

## 7. 对账框架

首期实现四类内部检查：

1. `ORDER_NET_VS_SETTLEMENT`：订单净实收是否等于结算总额。
2. `SETTLEMENT_VS_ALLOCATIONS`：四方 allocation 合计是否等于结算总额。
3. `ALLOCATION_VS_LEDGER`：allocation 的入账、释放和冲正累计是否一致。
4. `WALLET_VS_LEDGER`：钱包三个余额是否等于对应账本累计结果。

对账任务只读业务事实并 upsert 异常记录。后续 Airwallex adapter 增加：

- `PAYMENT_VS_PROVIDER`；
- `REFUND_VS_PROVIDER`；
- `PAYOUT_VS_PROVIDER`；
- `FX_QUOTE_VS_CONVERSION`。

## 8. API 与权限边界

本阶段优先实现内部 service 和管理查询接口，不开放提现或银行账户写接口。

- 消费者只能读取本人订单的结算摘要，不能看到其他收款方敏感账户信息。
- 医生、顾问只能读取本人 wallet 和 allocation。
- 机构管理人员只能读取其可见机构的 wallet 和 allocation。
- 平台财务管理员可以读取全局结算、账本和对账异常。
- 所有对象范围必须由服务端 actor context 校验，前端隐藏入口不构成授权。
- 资金订单、结算、allocation 和账本禁止硬删除。

## 9. 数据迁移

- 不复用旧分支 V10 至 V15 的迁移编号。
- 使用当前分支未占用的新迁移版本。
- 历史 settlement 默认不自动补记钱包，先标记为待迁移审查，避免在缺少完整支付或主体快照时制造资金事实。
- 新写入路径先双写现有金额字段和 minor-unit 字段；迁移并验证存量数据后再收紧非空约束。
- 每次迁移必须按 worktree 规则在新建的隔离空数据库验证，并打印数据库主机和数据库名。

## 10. 测试与验收

必须覆盖：

- 同一订单并发创建结算只生成一套 allocation 和账本流水；
- 四方金额对 USD 及不同币种精度均守恒；
- 结算配置变更不影响既有快照；
- 到期任务重复执行不重复释放；
- 并发释放和退款不会造成余额错乱或死锁；
- 多次部分退款及最终全额退款的累计冲正守恒；
- 可用余额不足时不产生可提现负余额并生成追偿异常；
- 钱包投影与账本累计一致；
- 跨医生、顾问和机构访问被拒绝；
- 对账任务可重复执行，不重复创建活动异常；
- 资金记录无法通过普通管理接口硬删除。

最小端到端验收链路为：

```text
成功支付 -> 订单完成 -> 创建四方快照 -> pending 入账
-> 到期释放 available -> 部分退款冲正 -> 内部对账一致
```

## 11. 后续阶段

本设计完成后再分别设计：

1. Airwallex Payments 收款、退款及 webhook adapter；
2. beneficiary/KYC、USD/CNY 路径及 Airwallex Transfer adapter；
3. 提现审核、冻结、出款任务和失败恢复；
4. 钱包、流水、提现及财务运营界面。

上述阶段必须以 Airwallex 对中国大陆主体、医疗行业、USD 收款及大陆收款人出款的书面确认，以及法务财税意见为前提。
