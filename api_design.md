# 个人管理系统 后端 HTTP 接口文档（as-built）

> 本文档与代码实现完全对齐（截至 2026-10-18），供 Web 前端 / **小程序端**开发参考。
> 技术栈实际为：Spring Boot 2.7 + MyBatis(XML) + H2 文件库 + JWT，端口 8089。
> 旧版设计建议稿已被本文档取代。

## 通用约定

| 项 | 约定 |
|---|---|
| 基础路径 | `/api`（PersonController 例外：`/persons`，早期遗留模块） |
| 鉴权 | 除 `/api/auth/**` 和 `POST /api/external/sync` 外，均需请求头 `Authorization: Bearer <token>` |
| 统一响应体 | `{ "code": 200, "msg": "success", "data": ... }`，**HTTP 状态恒为 200，业务成败看 code** |
| 错误码 | 200 成功；400 参数/业务错误（msg 为可直接展示的文案）；401 未登录/令牌过期；403 无权限；500 服务器错误 |
| 分页 | 仅收支列表分页：`pageNum`(默认1)、`pageSize`(默认10)，data 为 `{ "list": [...], "total": n }` |
| 日期 | `YYYY-MM-DD`；月份 `YYYY-MM`；时间 `YYYY-MM-DDTHH:mm:ss` |
| 数据隔离 | 所有业务表带 `user_id`，后端从 JWT 解析后自动注入，客户端不传、也传了无效 |

登录响应 data：`{ "token": "eyJ...", "nickname": "小q" }`，token 为 JWT（含 userId/username/exp）。

---

## 1. 鉴权 `/api/auth`

| 方法 | 路径 | 说明 | 请求体 |
|---|---|---|---|
| POST | /api/auth/register | 注册 | `{ "username", "password", "nickname" }` |
| POST | /api/auth/login | 登录 | `{ "username", "password" }` |

## 2. 主页统计 `/api/stats`

| 方法 | 路径 | 说明 | 参数 |
|---|---|---|---|
| GET | /api/stats/dashboard | 本月收支合计、净资产、最近 30 天收支流水(最多8条) | - |
| GET | /api/stats/income-expense | 收支趋势（按月） | `startMonth`、`endMonth`(YYYY-MM) |
| GET | /api/stats/expense-category | 支出分类占比 | `month`(YYYY-MM，默认当月) |

dashboard data：`{ "monthIncome", "monthExpense", "assetTotal", "investTotal", "recent": [{ "kind": "收入/支出", "amount", "category", "recordDate" }] }`

income-expense data：`[{ "month": "2026-05", "income": 15200.00, "expense": 6100.00 }]`

expense-category data：`[{ "category": "餐饮", "amount": 2350.00 }]`

## 3. 资产 `/api/asset`

资产字段：`{ id, name, category, value, changeDate, remark, createTime, updateTime }`
category 枚举：`现金 / 银行卡 / 股票 / 基金 / 房产 / 车辆 / 电子物品 / 其他`（现金、银行卡之外的类别支持卖出）

| 方法 | 路径 | 说明 | 请求体/参数 |
|---|---|---|---|
| GET | /api/asset/list | 全部资产 | - |
| POST | /api/asset | 新增（changeDate 为资产起始日期，折线从该日算起） | `{ "name", "category", "value", "changeDate", "remark" }` |
| PUT | /api/asset/{id} | 修改（联动重算历史） | 同新增 |
| DELETE | /api/asset/{id} | 删除（级联删个人历史数据；渠道同步来源的资产除外） | - |
| GET | /api/asset/trend | 各资产+总资产月度走势 | `startMonth`、`endMonth` |
| POST | /api/asset/{id}/sell | 卖出（限非现金/银行卡）：金额进入 `assetId` 资产，清除该资产历史 | `{ "price": 5000.00, "assetId": 2 }` |

trend data：`{ "months": ["2026-01",...], "assets": [{ "id", "name", "values": [...] }], "total": [...] }`

## 4. 支出 `/api/expense`

字段：`{ id, amount, category, recordDate, assetId, investmentId, assetName, investmentName, remark }`
category 枚举：`餐饮 / 交通 / 住房 / 购物 / 娱乐 / 医疗 / 教育 / 投资 / 其他`；**「投资」是特殊分类：只发生资产↔投资划转，不计入支出统计**，需带 investmentId

| 方法 | 路径 | 说明 | 请求体/参数 |
|---|---|---|---|
| GET | /api/expense/list | 分页列表 | `month`(必填)、`pageNum`、`pageSize`、`keyword`(模糊匹配备注) |
| POST | /api/expense | 新增（扣减 assetId 对应资产，检查余额） | `{ "amount", "category", "recordDate", "assetId", "investmentId?", "remark" }` |
| PUT | /api/expense/{id} | 修改（差额联动资产） | 同新增 |
| DELETE | /api/expense/{id} | 删除（回补资产） | - |
| POST | /api/expense/recognize | **AI 拍照识别**（multipart，字段名 `files`，≤9 张，单张≤10MB，总≤50MB） | 见下文 |

recognize data（数组，顺序与上传一致；一张图可识别出多笔逐笔展开）：

```json
[{ "amount": 42.50, "recordDate": "2026-10-18", "category": "餐饮",
   "assetHint": "招商银行储蓄卡", "assetId": 1, "assetName": "招商银行储蓄卡",
   "remark": "XX牛肉面", "error": null }]
```

- `error` 非空表示该张/该笔识别失败（坏图、非消费凭证），客户端展示错误并允许移除
- `assetId` 为后端按用户现金/银行卡资产自动匹配的结果，可能为 null（用户需手选）
- **识别不落库不扣款**，客户端确认后逐条走 `POST /api/expense` 正式创建
- 服务端多线程并行识别，整体一般 5~15s，小程序端需给足超时（≥60s）

## 5. 收入 `/api/income`

字段：`{ id, amount, category, recordDate, assetId, assetName, remark }`
（收入同样需指定到账资产 assetId，金额相应增加）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/income/list | 参数同支出（`month`、`pageNum`、`pageSize`、`keyword`） |
| POST | /api/income | `{ "amount", "category", "recordDate", "assetId", "remark" }` |
| PUT | /api/income/{id} | 同新增（差额联动资产） |
| DELETE | /api/income/{id} | 删除（回补资产） |

## 6. 投资 `/api/investment`

字段：`{ id, name, type, code, amount, currentValue, shares, price, sourceAssetId, sourceChannel, buyDate, remark }`
- type 枚举：`股票 / 基金 / 其他`
- `code`：归一化代码，如 `sh600519`、`sz000001`、`hk01810`（股票/基金才有）
- `sourceChannel`：渠道同步来源徽标（如"同花顺"），非空则该投资**只读**、变化由渠道推送
- 股票填 `shares`（股数）+ `price`（买入价，股票币种）；其他类型填 `amount`（买入金额，人民币）
- `sourceAssetId`：可选，买入时扣款的资金来源资产；不填则视为历史存续资产不扣款

| 方法 | 路径 | 说明 | 请求体/参数 |
|---|---|---|---|
| GET | /api/investment/list | 全部标的（**按当前总市值从大到小排序**；含渠道同步的只读标的；share-based 标的带 `dayProfit`=最新交易日盈亏，实时计算） | - |
| POST | /api/investment | 新增（校验 code 存在性；自动拉全部历史日K；写一笔 BUY 交易记录） | 字段见上 |
| PUT | /api/investment/{id} | 修改（**渠道来源的禁止修改**；份额/金额变化自动记 BUY/SELL 交易） | 同新增 |
| DELETE | /api/investment/{id} | 删除（级联删历史与交易记录；**渠道同步标的也可删除**，下次渠道推送会按持仓重建） | - |
| GET | /api/investment/{id}/trend | 某标的每日市值/价格走势 | `start`、`end`(可选，默认近30天) |
| GET | /api/investment/{id}/trades | 买卖交易记录（K线买卖标记数据源），按日期升序 | - |
| GET | /api/investment/{id}/profit-history | **买入后的历史盈亏**（实时计算不持久化）：逐日 市值−持仓成本；有台账的标的按交易台账逐日演进份额/成本（平均成本法），卖出清零后持仓期历史盈亏仍正确体现 | `start`、`end`(可选，默认首笔交易以来全部历史) |
| GET | /api/investment/profit-trend | 投资盈亏走势（全组合） | `start`、`end`(可选) |
| POST | /api/investment/{id}/sell | 卖出（股票/基金按份额、其他按金额），回款到 assetId 资产；**全额卖出不清仓删除**，行保留（份额/市值/投入清零）+ 记 SELL 交易；`tradeDate` 可指定过去日期补录历史卖出（交易与快照按该日期落，台账重算曲线） | `{ "assetId", "shares"?, "price"?, "amount"?, "tradeDate"? }` |
| POST | /api/investment/{id}/buy | **买入（加仓）**，仅股票/基金：股数/份额 + 单价（港股港币折人民币），投入=单价×数量×汇率；可选从 assetId 出资资产扣款（不选=外部资金）；`tradeDate` 默认今天，可指定过去日期补录历史买入（台账重算历史曲线）；加仓后市值按最新行情价计算。**历史日期（早于今天）的买入/卖出只记台账与历史曲线，不调整当前资产余额、不在历史日期补资产快照**（历史资金变动早已体现在当前余额中，再调会双计） | `{ "shares", "price", "tradeDate"?, "assetId"?, "remark"? }` |

trend data：`[{ "date": "2026-09-19", "value": 18320.50, "price": 183.2050 }]`（`price` 仅股票/基金类）

trades data：`[{ "id", "tradeType": "BUY|SELL", "tradeDate", "shares"?, "price"?, "amount", "targetAssetId"?, "remark" }]`，交易记录持久化于 `investment_trade` 表（存量投资启动时自动迁移出一条 BUY）

## 7. 行情 `/api/market`（服务端缓存：启动预热 + 每 20s 刷新 + 收盘落库）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/market/quote/{code} | 单标实时行情 |
| GET | /api/market/list | 全部缓存行情（`Map<code, MarketQuote>`） |
| POST | /api/market/validate | 校验代码是否存在，合法即入缓存。请求体 `{"code":"sh600519"}` |
| POST | /api/market/refresh | 手动刷新缓存 |
| POST | /api/market/record-daily | 手动触发收盘落库（正常由定时任务执行，无需调用） |

MarketQuote：`{ code, name, type, price, prevPrice, changePercent, updateTime }`

## 8. 汇率 `/api/fx`（美元/日元/港元/欧元/英镑 ↔ 人民币；当日缓存 + 10 分钟定时刷新 + 历史落库）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/fx/list | 全部币种最新汇率（`Map<币种, ExchangeRate>`） |
| POST | /api/fx/refresh | 手动刷新 |

ExchangeRate：`{ currency, rate, updateTime }`（相对 CNY）

## 9. 外部渠道同步 `/api/external`（同花顺持仓同步）

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | /api/external/channel/list | Bearer | 我的渠道列表（含 apiKey 前缀、上次同步时间/消息） |
| POST | /api/external/channel | Bearer | 添加渠道 `{ "channel": "THS", "accountName": "同花顺登录用户名", "remark" }`；同账户名重复添加会 400 |
| DELETE | /api/external/channel/{id} | Bearer | 删除渠道（已同步投资保留） |
| POST | /api/external/sync | **X-Api-Key** | 持仓推送（由备用电脑上的 ths_push_agent.py 调用，非登录态） |

推送请求体：`{ "channel": "THS", "account": "<账户名>", "positions": [{ "code", "name", "shares", "costPrice" }] }`
- 对账规则：持仓有系统无→自动新增；双方都有→股数差异按现价折算联动资产；系统有持仓无→不自动删除
- 限流：每 key 12 次/分钟

## 10. 健康 `/api/weight`、`/api/diet`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/weight/list | `start`、`end` 可选，按日期升序 |
| POST | /api/weight | `{ "recordDate", "weight", "note" }`（同日 upsert） |
| PUT / DELETE | /api/weight/{id} | 修改 / 删除 |
| GET | /api/diet/list | `date`(YYYY-MM-DD，必填) |
| GET | /api/diet/daily-summary | 热量走势：`[{ "date", "calories" }]`，参数 `start`、`end` |
| POST | /api/diet | `{ "recordDate", "mealType", "food", "calories" }` |
| PUT / DELETE | /api/diet/{id} | 修改 / 删除 |

---

## 小程序端接入注意事项

1. **域名与 HTTPS**：微信小程序正式版要求 request 域名备案 + HTTPS。建议后端挂域名 + nginx 反代 + 证书；开发阶段可在开发者工具勾选"不校验合法域名"
2. **token 存储**：用 `wx.setStorageSync('token', ...)`，请求时带 `Authorization` 头；401 时清理并跳登录页
3. **统一错误处理**：HTTP 200 + body.code 判断，`code !== 200` 时 toast `msg`（后端文案可直接展示）
4. **图片上传**：识图接口用 `wx.chooseMedia` 选图 + `wx.uploadFile`（name 填 `files`），`formData` 不需要额外字段；后端多线程处理整体 5~15s，`wx.uploadFile` 超时建议 ≥60s
5. **并发请求**：`wx.request` 无拦截器概念，自行封装 `request(url, { method, data, auth })` 工具函数
6. **金额**：后端返回 number（json 数值），大额注意 JS 精度（分单位显示或 toFixed(2)）
7. **图表**：小程序端无 ECharts 原生支持，可用 ec-canvas（ECharts 小程序版）或 uCharts，数据格式直接消费本文档 trend/dashboard 接口
8. **表格类 UI**：资产/投资/收支列表用小程序原生组件或 Vant Weapp，渠道同步来的投资行需置灰禁用编辑（`sourceChannel` 非空）

## 表 → 接口速查

| 表 | 接口 |
|---|---|
| sys_user | /api/auth/* |
| income | /api/income/* + /api/stats/* |
| expense | /api/expense/*（含 recognize）+ /api/stats/* |
| asset (+value_history) | /api/asset/*（含 trend/sell） |
| investment (+value_history) | /api/investment/*（含 trend/profit-trend/sell/buy） |
| external_channel | /api/external/channel/* + /api/external/sync |
| market_daily_quote | /api/market/*（服务端内部使用为主） |
| exchange_rate (+history) | /api/fx/* |
| body_weight / diet_record | /api/weight/*、/api/diet/* |
| daily_snapshot（净资产快照） | 定时任务维护，前端经 dashboard/趋势接口间接消费 |
