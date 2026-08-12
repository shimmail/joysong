# 专业目录使用说明 / Professional Catalog Guide

ACTIVE 医生与服务端管理上下文允许的机构法人，可在专业管理中心选择“专业目录 / Professional catalog”。目录只展示服务端逐对象授权返回的机构、医生、平台项目和机构项目；搜索仅过滤已返回内容。

- 机构可进入详情和医生列表，医生可继续查看其项目。
- 支持中英文搜索、无匹配、空目录、错误和重试状态。
- 403 与 404 使用相同不可用提示。
- 医生可撤回本人 `PENDING` 的 `JOIN` 或 `PROFILE_UPDATE` 申请；成功刷新，失败保留列表项。
- 目录不提供项目或机构项目创建、编辑、删除。

管理员系统、管理员接口实现与页面不变 / Admin system, admin API implementation, and pages are unchanged.
