# 航空快递揽收与派送管理系统（AEPDS）

大作业《航空快递揽收与派送管理系统》完整工程实现：Vue 3 前端 + Node/Express 后端 + SQLite 数据库，覆盖题目全部 10 项功能与 5 个服务划分，并带登录鉴权、角色权限与全流程自动化测试（e2e）。

## 运行

```bash
# 1. 后端（终端 A）
cd backend
npm install
npm start          # http://127.0.0.1:3001 ，同时托管前端 dist（若已 build）

# 2. 前端开发模式（终端 B，可选）
cd frontend
npm install
npm run dev        # http://localhost:5173 ，/api 自动代理到 3001

# 3. 生产构建
cd frontend && npm run build   # 产物 dist/ 由后端直接托管
```

Node 需 ≥ 22.5（使用内置 `node:sqlite`，启动参数 `--experimental-sqlite` 已写在 npm scripts 里）。
首次启动自动建表并写入**主数据**种子（产品运价、服务范围、航班、计费规则、枢纽、KPI 权重、片区网格、3 个客户、员工与各角色账号）；业务数据（订单/运单/账单/异常）不预置，全部由实际操作产生。老库直接启动即可，列迁移幂等自动升级。

## 账号（初始密码统一 123456，登录后可改）

| 账号 | 角色 | 可用模块 |
|---|---|---|
| `admin` | 系统管理员 | 全部 |
| `dispatch` | 调度员 | 工作台/下单/派单/揽收/中转/派送/跟踪 |
| `courier` | 快递员 | 工作台/揽收/派送/跟踪 |
| `claims` | 理赔专员 | 工作台/异常理赔/跟踪 |
| `finance` | 财务人员 | 工作台/月结账单/客户账户/运营报表 |
| `ops` | 运营管理员 | 工作台/绩效排名/运营报表/跟踪 |
| `zhangsan` / `lisi` / `wangwu` | 寄件客户（张三散客，李四/王五月结） | 我的账户/下单/跟踪，仅见本人数据 |

鉴权：全部 API 需 `Authorization: Bearer <token>`；服务端按「路径前缀网关 + 路由内角色」双层拦截，客户数据按本人过滤。报表导出（window.open）允许 `?token=` 传参。

## 全流程测试（e2e）

```bash
# 后端已启动后，在仓库根目录执行（默认连 3001 端口，可用 APED_BASE 改）
node e2e.js
# 自定义端口示例：APED_BASE=http://127.0.0.1:3101/api node e2e.js
```

建议对全新数据库跑（删 `backend/aepds.db` 后重启后端）。脚本带 44 条断言，覆盖：登录与越权拦截 → 月结客户下单 → 长×宽×高体积重试算 → 自动派单 → 接单 → 揽收计费 → 中转（真实航班 MU5101/CA1832/ZH9502 + 安检）→ 派件签收 → 轨迹 → 月结账单生成/明细/部分核销/结清 → 异常定责试算 → 理赔审批赔付（含已签收运单理赔关单回归）→ 绩效 → CSV 导出。最新验证结果见 `VERIFY.md`。

## 目录

```
aped/
├── backend/
│   ├── server.js          # 入口：装配 5 个服务 + 按路径前缀的角色网关 + 静态托管
│   ├── db.js              # SQLite 建表 + 幂等列迁移 + 主数据种子
│   ├── core/              # pricing（计费）/ assign（派单 SQL）/ routing（分拨路由）/ kpi（绩效）/ claim-engine（定责理赔）/ auth / helpers
│   └── services/          # auth / order / billing / dispatch / claim / performance
├── frontend/
│   └── src/views/         # 14 个页面：工作台/下单/派单/揽收/中转/派送/跟踪/异常理赔/客户账户/月结账单/绩效/报表/我的账户/登录
├── e2e.js                 # 全流程断言测试
└── VERIFY.md              # 最近一次完整验证记录（命令 + 输出 + 修复清单）
```

## 功能对照（题目 10 项）

| 功能 | 后端端点（均需登录） | 前端页面 |
|---|---|---|
| ① 下单与取件预约 | `POST/GET /api/orders`、`POST /api/orders/quote`、`POST /api/orders/:id/cancel` | 客户下单 |
| ② 运单生成与计费 | 揽收时生成（计费服务 `createWaybillForOrder`）、`GET /api/rates`、`GET /api/pricing/rules` | 揽收登记、下单试算 |
| ③ 揽收任务分配与揽收登记 | `/api/pickup/assign`（自动定人）、`/accept`、`/rollback`、`/api/pickup` | 派单调度、揽收登记 |
| ④ 分拣中转登记 | `GET/POST /api/transit`、`/api/transit/suggest`（路由建议） | 分拣中转 |
| ⑤ 派件任务分配与签收 | `/api/delivery/assign`、`/sign`、`/fail` | 派送签收 |
| ⑥ 异常件处理与理赔 | `/api/exceptions`、`/:id/assess`（定责试算）、`/:id/close`、`/api/claims`、`/claims/approve` | 异常理赔 |
| ⑦ 运单全程轨迹查询 | `GET /api/tracking/:no`（运单号或订单号） | 运单跟踪 |
| ⑧ 客户账户与月结账单 | `/api/customers`、`/api/billing`、`/:id`（明细+流水）、`/items/:id/freeze`、`/settle` | 客户账户、月结账单、我的账户 |
| ⑨ 快递员揽派绩效统计排名 | `GET /api/performance` | 绩效排名、运营报表 |
| ⑩ 运营统计报表导出 | `/api/reports/summary`、`/preview`、`/export?type=performance\|waybills\|bills\|exceptions\|transit&format=csv\|xls` | 运营报表 |

服务划分对应：订单服务（①⑦⑧账户）｜计费服务（②⑧账单）｜揽派调度（③④⑤）｜异常理赔（⑥）｜绩效统计（⑨⑩）。

## 计费规则

- 基础运费 =（首重价 + **阶梯续重**）× 产品时效系数 × 区域系数（同城 0.9 / 省内 1.0 / 同大区 1.15 / 跨大区 1.35 / 偏远 1.8）
- 产品：次日达 ×1.2（24h）、隔日达 ×1.0（48h）、经济件 ×0.85（72h，首重价更低）；运价在 `products` 表可配
- **体积重量 = 长×宽×高(cm) ÷ 6000**，与实重取大后进位整公斤计费（揽收/试算录入长宽高；`volumeL` 为兼容入参，单位升，按 ×1000 换算 cm³ 后再除 6000）
- 附加费：燃油 8%、保价费 = 保价额 × 3‰、包装 ¥2、超重（>30kg）¥15、超长（最长边 >100cm）¥20
- 月结客户运费类享 95 折协议折扣；全单不低于最低一票 ¥12；每项逐项出明细并随运单 `fee_snapshot` 落库可对账

## 设计说明（前端视觉）

「货站调度室」主题：单据式细线表格（不用圆角卡片）、等宽字体专用于单号/金额/时刻、全站唯一深色构件为顶部「出港信息板」（琥珀等宽数字）。配色：纸白 #F4F5F2 / 墨 #1E2833 / 货站蓝 #2B4C6F / 琥珀 #D98E2B / 异常红 #C0453A。

## 已验证

- `node e2e.js`：44 条断言全部通过（详见 `VERIFY.md`，含角色权限矩阵 18/18 抽查）
- 前端 `npm run build` 成功；后端同库重启迁移幂等（0 条重复迁移、无报错）
- 边界：重量 >1000kg 拒收、理赔超保价上限拒绝、重复揽收/签收拦截、无 token 401、越权角色 403、无效航班拒绝
