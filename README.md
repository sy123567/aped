# 航空快递揽收与派送管理系统（AEPDS）

大作业《航空快递揽收与派送管理系统》完整工程实现：Vue 3 前端 + Node/Express 后端 + SQLite 数据库，覆盖题目全部 10 项功能与 5 个服务划分。

## 运行

```bash
# 1. 后端（终端 A）
cd backend
npm install
npm start          # http://127.0.0.1:3001 ，同时托管前端 dist

# 2. 前端开发模式（终端 B，可选）
cd frontend
npm install
npm run dev        # http://localhost:5173 ，/api 自动代理到 3001

# 3. 生产构建
cd frontend && npm run build   # 产物 dist/ 由后端直接托管
```

Node 需 ≥ 22.5（使用内置 `node:sqlite`，启动参数 `--experimental-sqlite` 已写在 npm scripts 里）。
首次启动自动建表并写入种子数据（3 个订单、1 张在途运单、6 名员工、3 个客户）。

## 目录

```
aepds/
├── backend/
│   ├── server.js      # 全部 REST API（约 300 行，含计费规则与业务校验）
│   ├── db.js          # SQLite 建表 + 种子数据
│   └── aepds.db       # 运行后生成
├── frontend/
│   ├── src/api.js     # API 封装
│   ├── src/styles.css # 设计 token（货站调度室视觉语言）
│   └── src/views/     # 8 个页面
└── e2e.js             # 全流程贯通测试（node e2e.js）
```

## 功能对照（题目 10 项）

| 功能 | 后端端点 | 前端页面 |
|---|---|---|
| ① 下单与取件预约 | `POST/GET /api/orders` | 客户下单 |
| ② 运单生成与计费 | `POST /api/pickup`（含计费） | 揽收登记 |
| ③ 揽收任务分配与揽收登记 | `/api/pickup/assign`、`/api/pickup` | 客户下单（派单）+ 揽收登记 |
| ④ 分拣中转登记 | `POST /api/transit` | 运单跟踪 |
| ⑤ 派件任务分配与签收 | `/api/delivery/assign`、`/api/delivery/sign`、`/api/delivery/fail` | 派送签收 |
| ⑥ 异常件处理与理赔 | `/api/exceptions`、`/api/claims`、`/api/claims/approve` | 异常理赔 |
| ⑦ 运单全程轨迹查询 | `GET /api/tracking/:no` | 运单跟踪 |
| ⑧ 客户账户与月结账单 | `/api/billing`、`/api/billing/generate`、`/api/billing/settle` | 月结账单 |
| ⑨ 快递员揽派绩效统计排名 | `GET /api/performance` | 绩效报表 |
| ⑩ 运营统计报表导出 | `GET /api/reports/export?type=performance|waybill` | 绩效报表（导出 CSV） |

服务划分对应：订单服务（①）｜计费服务（②⑧）｜揽派调度（③④⑤）｜异常理赔（⑥）｜绩效统计（⑨⑩）。

## 计费规则（与用例描述一致）

- 首重 1kg ¥12，续重 ¥4/kg
- 体积重量 = 体积(L) ÷ 6000，与实重取大计费
- 时效系数：次日达 ×1.2、隔日达 ×1.0、经济件 ×0.85
- 保价费 = 保价额 × 3‰；理赔上限 = 保价额

## 设计说明（前端视觉）

「货站调度室」主题：单据式细线表格（不用圆角卡片）、等宽字体专用于单号/金额/时刻、全站唯一深色构件为顶部「出港信息板」（琥珀等宽数字）。配色：纸白 #F4F5F2 / 墨 #1E2833 / 货站蓝 #2B4C6F / 琥珀 #D98E2B / 异常红 #C0453A。

## 已验证

- `node e2e.js`：下单→派单→接单→揽收计费→中转→派件→签收→轨迹→账单→异常理赔→绩效→CSV 导出全流程通过
- 无头浏览器逐页截图：0 个 JS 报错
- 边界：重量 >1000kg 拒收、理赔金额超保价拒付、重复揽收/签收拦截
