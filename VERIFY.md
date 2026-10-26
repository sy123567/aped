# VERIFY.md — 验证记录（2026-10-26）

环境：Node v24.22.1（≥22.5，使用内置 `node:sqlite`）、npm 10.9.4、Linux。
验证后已清理运行产物（`aepds.db*`、`node_modules/`、`frontend/dist/` 均已删除，仓库恢复干净源码状态）。

## 1. 后端启动（全新数据库，临时端口 3101）

```bash
cd backend && npm install
rm -f aepds.db aepds.db-wal aepds.db-shm
PORT=3101 node --experimental-sqlite server.js
curl http://127.0.0.1:3101/api/health
# {"ok":true,"data":{"services":["订单服务","计费服务","揽派调度","异常理赔","绩效统计"]}}
```

启动日志含 `[db] migrate transit_logs.security`（本轮新增安检列）及全部主数据种子
（products / service_areas / flights / pricing_rules / routing_hubs / kpi_weights / grids / 客户与员工 / 各角色账号）。

**迁移幂等复验**：不删库同机重启，`grep -c migrate` = 0、无 fail/error 输出、health 正常。

## 2. e2e 全流程断言

```bash
cd ~/workspace/aped_src
APED_BASE=http://127.0.0.1:3101/api node e2e.js
```

真实输出（节选结构，逐条断言）：

```
== 0. 健康检查与鉴权 ==
  ✓ 健康检查返回 5 个服务
  ✓ 预期失败且确实失败：GET /orders（未登录或登录已过期）
  ✓ 预期失败且确实失败：POST /auth/login（账号或密码错误）
  ✓ admin/finance/claims/ops 均可登录
  ✓ 预期失败且确实失败：POST /billing/generate（当前角色（courier）无此操作权限）
== 1. 选取月结客户 + 运费试算（长×宽×高体积重） ==
  月结客户：李四（id=2）
  ✓ 体积重 = 60×50×40÷6000 = 20kg
  ✓ 计费重取大 = 20kg
  ✓ volumeL 兼容换算：12L×1000÷6000 = 2kg
== 2. 下单 → 自动派单 → 接单 → 揽收计费 ==
  ✓ 下单成功 SO26102600001，预估运费 ¥33.24
  ✓ 自动派单成功 1 单（快递员小李，跨区调度，在途 0/20）
  ✓ 揽收任务为待接单
  ✓ 运单生成 8826102600001；揽收计费重 = 体积重 20kg；计费明细完整，运费 ¥141.12
== 3. 分拣中转（真实航班 + 安检） ==
  ✓ 预期失败且确实失败：POST /transit（航班 CA1701 不在航班主数据中）
  ✓ 中转登记成功（CA1832，已过安检，含路由建议）
== 4. 派件 → 签收 → 轨迹 ==
  ✓ 派件任务自动分配 1 单；运单已签收；全程轨迹 6 条（≥5）；轨迹含安检记录
== 5. 月结账单：生成 → 明细 → 部分核销 → 结清 ==
  ✓ 账单 BILL2610260001 金额 ¥141.12 ≥ 本票运费；明细含本票运单
  ✓ 部分核销 10 元 → 部分核销；剩余全额核销 → 已结清；核销流水 2 条
== 6. 第二票：签收前异常 → 定责试算 → 理赔审批赔付 ==
  ✓ 异常登记成功；定责：承运方责任；定责依据引用安检记录（security 落库判定）
  ✓ 试算：保价 1000 × 中度 0.4 = ¥400；理赔单核定 ¥400；审批通过并赔付；理赔后运单已关闭
== 7. 回归：已签收运单发起理赔并关单（旧状态机曾回滚） ==
  ✓ 已签收票理赔核定：保价 500 × 轻微 0.2 = ¥100；审批成功（不回滚）；运单转已关闭
== 8. 绩效与报表导出 ==
  ✓ 绩效返回 2 名快递员；权重 4 项齐全；权重和 ≈ 1.000；揽收量合计 2
  ✓ 运营汇总运单量 2；绩效 CSV 含表头与数据行；运单 CSV 含两票运单

E2E PASS（断言 44 条全部通过）
```

## 3. 角色权限矩阵抽查（网关修复后，18/18 符合预期）

```
✓ courier    GET /pickup/tasks      -> 200    ✓ courier    GET /billing         -> 403
✓ courier    GET /board             -> 200    ✓ dispatch   GET /exceptions       -> 403
✓ dispatch   GET /dispatch/capacity -> 200    ✓ claims     GET /billing         -> 403
✓ dispatch   GET /transit           -> 200    ✓ finance    GET /pickup/tasks    -> 403
✓ claims     GET /exceptions        -> 200    ✓ ops        GET /transit         -> 403
✓ finance    GET /billing           -> 200    ✓ lisi(客户) GET /pickup/tasks    -> 403
✓ finance    GET /customers         -> 200    ✓ lisi(客户) GET /orders          -> 200
✓ finance    GET /reports/summary   -> 200    ✓ lisi(客户) GET /billing         -> 200（仅本人）
✓ ops        GET /performance       -> 200
✓ ops        GET /reports/summary   -> 200
```

修复前：claims/courier/ops 会被前置服务的 `allow()` 网关串行拦截，自己模块的接口也返回 403。

## 4. 前端构建

```bash
cd frontend && npm install && npm run build
# vite v6.4.5 building for production...
# ✓ 40 modules transformed.
# dist/index.html                 0.43 kB │ gzip:  0.33 kB
# dist/assets/index-*.css         8.47 kB │ gzip:  2.62 kB
# dist/assets/index-*.js        196.85 kB │ gzip: 63.88 kB
# ✓ built in 2.37s
```

## 5. 本轮修复清单（详见 改动说明.md 第九节）

1. `server.js` 角色网关串行拦截 → 按路径前缀分发 `gate()`（系统性缺陷，非 admin 角色曾大面积 403）。
2. `claim-engine.js` 查不存在的 `transit_logs.desc_text` → 改安检列方案：`db.js` 加 `security` 列迁移，`dispatch.js` 中转登记写入，引擎查 `security=1`（events 文本兜底）。
3. `helpers.js` 状态机 `已签收 → 已关闭` 放行，签收后理赔关单不再回滚。
4. `e2e.js` 全量重写（多角色 token、月结客户、真实航班、44 条断言、含负向用例）。
5. 体积重量改长×宽×高(cm)÷6000：`pricing.js`/`helpers.js`/`billing.js`（快照 v3）+ `Pickup.vue`/`Orders.vue` 三边录入；`volumeL` 保留升单位兼容换算。
6. 薄弱页补全：`Performance.vue`（鉴权改走 api、权重说明、得分拆解、未入排名原因）、`Billing.vue`（真实客户、明细/冻结/核销流水/部分核销）；`Orders.vue` 客户选择+试算；`Exceptions.vue` 定责试算+驳回+证据；`Delivery.vue` 去除无效手选快递员；`Tracking.vue` 收件人字段/角色显隐；`Customers.vue` 越权按钮显隐；`Dashboard.vue` 过期文案清理。
7. 后端校验补强：`dispatch.js` 签收/派送异常的状态与类型校验、业务错误统一 400；`billing.js` 已结清账单禁止冻结明细。
8. 新增 `.gitignore`；README 与 改动说明 已与最终代码同步。
