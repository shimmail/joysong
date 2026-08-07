# 真实支付外部配置清单

> 更新时间：2026-08-07
> 当前可启用渠道：Stripe Hosted Checkout
> Android/iOS：共用 Flutter HTTPS 跳转，不需要在 App 内保存 Stripe 密钥

## 1. Stripe 商户后台

1. 创建 Stripe 账户并完成企业主体、受益人、银行账户和 KYC 审核。
2. 先使用 Test mode，在 Developers / API keys 获取 `sk_test_...`。
3. 在 Developers / Webhooks 创建：

   ```text
   https://<API公网域名>/api/payment-webhooks/STRIPE
   ```

4. 订阅以下事件：

   ```text
   checkout.session.completed
   checkout.session.async_payment_succeeded
   checkout.session.async_payment_failed
   checkout.session.expired
   ```

5. 保存该 Endpoint 独立的 signing secret：`whsec_...`。
6. 在 Checkout/Payment methods 中开启商户主体、币种和地区实际允许的支付方式。

官方资料：[API 密钥](https://docs.stripe.com/keys)、[创建 Checkout Session](https://docs.stripe.com/api/checkout/sessions/create)、[Webhook](https://docs.stripe.com/webhooks)、[上线清单](https://docs.stripe.com/get-started/checklist/go-live)。

## 2. 服务端环境变量

测试环境使用 `sk_test_...` 和测试 Webhook secret；生产环境必须换成独立的 live key/endpoint：

```text
SPRING_PROFILES_ACTIVE=prod
PAYMENT_MODE=live
STRIPE_ENABLED=true
STRIPE_SECRET_KEY=sk_live_xxx
STRIPE_WEBHOOK_SECRET=whsec_xxx
STRIPE_SUCCESS_URL=https://pay.example.com/payment/success?session_id={CHECKOUT_SESSION_ID}
STRIPE_CANCEL_URL=https://pay.example.com/payment/cancel
STRIPE_PRODUCT_NAME=Joysong medical service
PAYMENT_RECONCILIATION_ENABLED=true
```

可选：

```text
STRIPE_API_VERSION=
STRIPE_API_BASE=https://api.stripe.com
STRIPE_WEBHOOK_TOLERANCE_SECONDS=300
PAYMENT_RECONCILIATION_DELAY_MS=60000
PAYMENT_RECONCILIATION_STALE_SECONDS=120
```

要求：

- API、success、cancel 和 webhook 地址必须是公网 HTTPS；
- Secret Key 和 Webhook Secret 只能进入服务端 Secret Manager/容器 Secret；
- 不得写进 Flutter、Git、构建日志或普通配置文件；
- 反向代理必须原样转发 webhook 请求体和 `Stripe-Signature`；
- 防火墙允许服务端访问 `https://api.stripe.com`。

## 3. Flutter Android/iOS 构建参数

```text
--dart-define=APP_ENV=production
--dart-define=API_BASE_URL=https://api.example.com/api
--dart-define=PAYMENT_PROVIDERS=STRIPE
--dart-define=PAYMENT_REDIRECT_HOSTS=checkout.stripe.com
```

没有传 `PAYMENT_PROVIDERS` 时，Debug 和 Release 都不会展示支付渠道；`DEMO` 已被客户端拒绝。

当前方案使用系统浏览器打开 Stripe Checkout。App 回到前台后会主动查单，只有服务端返回 `SUCCEEDED` 才显示成功。若希望支付完成页自动回到 App，还需要：

- Android：为 success/cancel 域名配置 App Links 和 `assetlinks.json`；
- iOS：配置 Associated Domains、Universal Links 和 `apple-app-site-association`；
- Stripe 的 success/cancel 页面跳转到已验证的 Universal/App Link。

## 4. 上线验收

- Test mode：成功、用户取消、卡片失败、异步支付、重复 webhook、回调延迟、主动查单、部分/全额退款；
- 幂等：同一个 App 请求和服务端补偿不得生成第二笔扣款；
- 金额：Webhook/查单的币种和最小单位金额必须与本地支付记录一致；
- 安全：伪造签名、过期签名和非白名单跳转必须被拒绝；
- Live mode：使用真实小额支付和退款验收，再逐步放量；
- 监控：渠道错误率、Webhook 失败/延迟、长期 `PROCESSING`、退款失败和对账差异告警。

## 5. 尚不能仅靠配置启用的渠道

`PAYPAL`、`WECHAT_PAY`、`ALIPAY` 目前没有完整服务端适配器和双端 SDK/专用回调协议。不要把它们加入 `PAYMENT_PROVIDERS`。它们分别还需要：

- PayPal：Business 主体、REST App client ID/secret、Webhook ID、Orders v2 create/capture/query/refund；
- 微信支付：开放平台 AppID、商户号、API v3 key、商户证书/私钥、平台公钥、包名/签名、Bundle ID/Universal Link、OpenSDK；
- 支付宝：应用签约、AppID、RSA2 私钥与支付宝公钥/证书、notify URL、Android/iOS SDK 和 App Scheme。

## 6. 商务与资金边界

- Stripe 收款账户必须属于 Stripe 支持的国家/地区。若只有中国大陆公司主体，应先确认合法可用的跨境收款主体和银行账户；不要在主体审核前切换 live mode。[Stripe 支持地区](https://stripe.com/global)
- 当前实现把用户付款收入平台 Stripe 商户余额；数据库中的 `SETTLED` 仍是业务账面结算，不代表已向机构或医生真实打款。
- 真实分账/代付需要另行开通并开发 Stripe Connect，或接入微信/支付宝服务商分账能力，同时处理机构 KYC、银行账户、税务、对账和失败补偿。
