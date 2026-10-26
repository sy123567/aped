// db.js — SQLite 连接、建表、列迁移、种子数据
import { DatabaseSync } from 'node:sqlite';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const db = new DatabaseSync(path.join(__dirname, 'aepds.db'));

db.exec(`
PRAGMA journal_mode = WAL;
CREATE TABLE IF NOT EXISTS customers (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL, phone TEXT, addr TEXT,
  monthly INTEGER DEFAULT 0,            -- 1=月结客户
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS employees (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  code TEXT UNIQUE, name TEXT, role TEXT  -- courier/dispatcher/claims/finance/ops
);
CREATE TABLE IF NOT EXISTS orders (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  order_no TEXT UNIQUE,
  customer_id INTEGER,
  sender TEXT, recv_name TEXT, recv_phone TEXT, recv_addr TEXT,
  category TEXT, weight REAL, slot TEXT,          -- 取件时段
  status TEXT DEFAULT '待派单',                     -- 待派单/待揽收/已揽收/已取消
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS waybills (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  waybill_no TEXT UNIQUE, order_id INTEGER,
  product TEXT,          -- 次日达/隔日达/经济件
  first_kg REAL, first_price REAL, cont_price REAL, vol_divisor INTEGER DEFAULT 6000,
  insured REAL DEFAULT 0,
  fee REAL, fee_detail TEXT,
  status TEXT DEFAULT '待揽收',
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS pickup_tasks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  order_id INTEGER, courier_id INTEGER,
  status TEXT DEFAULT '待接单',   -- 待接单/已接单/已揽收/已回滚
  assigned_at TEXT, accepted_at TEXT
);
CREATE TABLE IF NOT EXISTS transit_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  waybill_no TEXT, node TEXT, next_hop TEXT, flight TEXT, operator_id INTEGER,
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS delivery_tasks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  waybill_no TEXT, courier_id INTEGER, receiver TEXT, addr TEXT,
  status TEXT DEFAULT '待派送',   -- 待派送/派送中/已签收/派送异常
  signer TEXT, sign_way TEXT, signed_at TEXT
);
CREATE TABLE IF NOT EXISTS exceptions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  waybill_no TEXT, type TEXT,      -- 拒收/破损/丢失
  photos TEXT, desc_text TEXT,
  status TEXT DEFAULT '待处理',     -- 待处理/待审批/已理赔/已关闭
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS claims (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  exception_id INTEGER, amount REAL, rule TEXT,
  status TEXT DEFAULT '待审批',     -- 待审批/已通过/已驳回/已赔付
  approved_by TEXT, paid_at TEXT
);
CREATE TABLE IF NOT EXISTS bills (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  bill_no TEXT UNIQUE, customer_id INTEGER, period TEXT,
  amount REAL, item_count INTEGER,
  status TEXT DEFAULT '待确认',     -- 待确认/已确认/部分核销/已结清
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  waybill_no TEXT, order_no TEXT, node TEXT, desc_text TEXT,
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS products (            -- 时效产品与运价（可配置）
  code TEXT PRIMARY KEY, name TEXT, coef REAL, promise_hours INTEGER,
  first_kg REAL, first_price REAL, cont_price REAL, vol_divisor INTEGER,
  sort INTEGER DEFAULT 0, active INTEGER DEFAULT 1
);
CREATE TABLE IF NOT EXISTS service_areas (       -- 服务范围（省/市/区）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  province TEXT, city TEXT, district TEXT, supported INTEGER DEFAULT 1
);
CREATE TABLE IF NOT EXISTS flights (             -- 航班主数据
  flight_no TEXT PRIMARY KEY, dep TEXT, arr TEXT, etd TEXT, atd TEXT
);
CREATE TABLE IF NOT EXISTS bill_items (          -- 月结账单明细（运单级）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  bill_id INTEGER, waybill_no TEXT, product TEXT, fee REAL,
  signed_at TEXT, frozen INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS receipts (            -- 回款核销流水
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  bill_id INTEGER, amount REAL, paid_at TEXT, voucher TEXT, operator TEXT
);
CREATE TABLE IF NOT EXISTS pricing_rules (       -- 计费规则（区域系数/燃油/最低收费/阶梯）
  key TEXT PRIMARY KEY, value REAL, unit TEXT, note TEXT
);
CREATE TABLE IF NOT EXISTS routing_hubs (        -- 分拨枢纽（三段码/分拨路由用）
  code TEXT PRIMARY KEY, name TEXT, type TEXT,        -- air/land
  province TEXT, city TEXT, coverage TEXT             -- coverage: 逗号分隔覆盖省份
);
CREATE TABLE IF NOT EXISTS kpi_weights (         -- 绩效指标权重
  metric TEXT PRIMARY KEY, label TEXT, weight REAL, direction INTEGER, note TEXT
);
CREATE TABLE IF NOT EXISTS users (               -- 账号
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT UNIQUE NOT NULL, pwd_hash TEXT, salt TEXT,
  role TEXT NOT NULL,                            -- admin/dispatcher/courier/claims/finance/ops
  employee_id INTEGER, display_name TEXT,
  active INTEGER DEFAULT 1, created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS sessions (            -- 登录态
  token TEXT PRIMARY KEY, user_id INTEGER, role TEXT,
  created_at TEXT, expires_at TEXT
);
CREATE TABLE IF NOT EXISTS grids (               -- 片区网格（地址 -> 网格码）
  grid_code TEXT PRIMARY KEY, province TEXT, city TEXT, district TEXT
);

-- 快递员实时负载视图：分配规则直接查它，不在代码里算
CREATE VIEW IF NOT EXISTS v_courier_load AS
SELECT e.id, e.code, e.name, e.grid_code,
       (SELECT COUNT(*) FROM pickup_tasks t
          WHERE t.courier_id = e.id AND t.status IN ('待接单','已接单')) AS load,
       20 AS capacity
FROM employees e WHERE e.role = 'courier';

-- 待派单订单的候选排序视图（同一订单内按：同片区优先 -> 负载低优先 -> 工号定序）
CREATE VIEW IF NOT EXISTS v_assign_pickup AS
WITH pending AS (
  SELECT o.id AS order_id, o.order_no, o.recv_addr, o.slot, o.weight,
         (SELECT g.grid_code FROM grids g
            WHERE o.recv_addr LIKE '%' || g.district || '%' LIMIT 1) AS order_grid
  FROM orders o WHERE o.status = '待派单'
)
SELECT p.order_id, p.order_no, p.recv_addr, p.slot, p.weight, p.order_grid,
       c.id AS courier_id, c.code AS courier_code, c.name AS courier_name,
       c.grid_code, c.load, c.capacity,
       CASE WHEN c.grid_code = p.order_grid THEN 1 ELSE 0 END AS same_area,
       ROW_NUMBER() OVER (
         PARTITION BY p.order_id
         ORDER BY CASE WHEN c.grid_code = p.order_grid THEN 1 ELSE 0 END DESC, c.load ASC, c.id ASC
       ) AS rn
FROM pending p JOIN v_courier_load c ON c.load < c.capacity;
CREATE INDEX IF NOT EXISTS ix_events_wb ON events(waybill_no);
CREATE INDEX IF NOT EXISTS ix_waybills_no ON waybills(waybill_no);
CREATE INDEX IF NOT EXISTS ix_orders_no ON orders(order_no);
CREATE INDEX IF NOT EXISTS ix_bill_items_bill ON bill_items(bill_id);
CREATE INDEX IF NOT EXISTS ix_receipts_bill ON receipts(bill_id);
CREATE INDEX IF NOT EXISTS ix_transit_wb ON transit_logs(waybill_no);
`);

// ---- 列迁移：老库升级（SQLite 没有 IF NOT EXISTS 加列，靠捕获重复列错误实现幂等） ----
function addCol(table, col, def) {
  try {
    db.exec(`ALTER TABLE ${table} ADD COLUMN ${col} ${def}`);
    console.log(`[db] migrate ${table}.${col}`);
  } catch (e) {
    if (!/duplicate column/i.test(e.message)) {
      console.warn('[db] addCol failed', table, col, e.message);
    }
  }
}
addCol('orders', 'est_fee', 'REAL');
addCol('orders', 'area_note', 'TEXT');
addCol('orders', 'cancel_reason', 'TEXT');
addCol('orders', 'cancelled_at', 'TEXT');
addCol('waybills', 'promise_time', 'TEXT');
addCol('waybills', 'signed_at', 'TEXT');
addCol('waybills', 'fee_snapshot', 'TEXT');
addCol('pickup_tasks', 'rollback_reason', 'TEXT');
addCol('employees', 'grid_code', 'TEXT');
addCol('bills', 'paid_amount', 'REAL DEFAULT 0');
addCol('bills', 'receipt_count', 'INTEGER DEFAULT 0');
addCol('events', 'order_no', 'TEXT');
addCol('exceptions', 'closed_at', 'TEXT');
addCol('claims', 'evidence', 'TEXT');
addCol('users', 'customer_id', 'INTEGER');
addCol('exceptions', 'severity', "TEXT DEFAULT '中度'");
addCol('waybills', 'dest_zone', 'TEXT');
addCol('transit_logs', 'security', 'INTEGER DEFAULT 0'); // 1=已过航空安检（定责引擎读取，老库靠 events 文本兜底）

// order_no 列加完才能建这个索引
try {
  db.exec('CREATE INDEX IF NOT EXISTS ix_events_order ON events(order_no);');
} catch (e) {
  console.warn('[db] index events.order_no skipped:', e.message);
}

// ---- 历史数据回填 ----
db.exec(`
UPDATE waybills SET promise_time = datetime(created_at, '+' || (
  CASE product WHEN '次日达' THEN 24 WHEN '隔日达' THEN 48 WHEN '经济件' THEN 72 ELSE 48 END
) || ' hours')
WHERE promise_time IS NULL;
UPDATE waybills SET signed_at = (
  SELECT d.signed_at FROM delivery_tasks d
  WHERE d.waybill_no = waybills.waybill_no AND d.signed_at IS NOT NULL
  ORDER BY d.id DESC LIMIT 1
) WHERE signed_at IS NULL;
UPDATE bills SET paid_amount = CASE WHEN status='已结清' THEN amount ELSE paid_amount END
WHERE paid_amount IS NULL OR paid_amount = 0;
`);

// ---- 种子数据 ----
const cnt = (t) => db.prepare(`SELECT COUNT(*) c FROM ${t}`).get().c;

if (cnt('products') === 0) {
  const ins = db.prepare(
    'INSERT INTO products (code,name,coef,promise_hours,first_kg,first_price,cont_price,vol_divisor,sort) VALUES (?,?,?,?,?,?,?,?,?)'
  );
  ins.run('P_ND', '次日达', 1.2, 24, 1, 12, 4, 6000, 1);
  ins.run('P_GD', '隔日达', 1.0, 48, 1, 12, 4, 6000, 2);
  ins.run('P_JJ', '经济件', 0.85, 72, 1, 10, 3.5, 6000, 3);
  console.log('[db] seeded products');
}

if (cnt('service_areas') === 0) {
  const ins = db.prepare('INSERT INTO service_areas (province,city,district,supported) VALUES (?,?,?,?)');
  [['浙江省', '杭州市', '滨江区'], ['浙江省', '杭州市', '西湖区'], ['浙江省', '杭州市', '拱墅区'],
   ['上海市', '上海市', '浦东新区'], ['上海市', '上海市', '徐汇区'],
   ['北京市', '北京市', '朝阳区'], ['广东省', '深圳市', '南山区']]
    .forEach((a) => ins.run(a[0], a[1], a[2], 1));
  // 一个暂不支持上门揽收的受限区县（服务范围校验用）
  ins.run('西藏自治区', '那曲市', '安多县', 0);
  console.log('[db] seeded service_areas');
}

if (cnt('flights') === 0) {
  const ins = db.prepare('INSERT INTO flights (flight_no,dep,arr,etd,atd) VALUES (?,?,?,?,?)');
  ins.run('MU5101', '杭州萧山', '上海虹桥', '08:20', '09:15');
  ins.run('CA1832', '杭州萧山', '北京首都', '10:40', '13:05');
  ins.run('ZH9502', '上海虹桥', '深圳宝安', '14:10', '16:35');
  console.log('[db] seeded flights');
}

// ---- 计费规则（区域系数 / 燃油 / 最低收费 / 附加费） ----
if (cnt('pricing_rules') === 0) {
  const ins = db.prepare('INSERT INTO pricing_rules (key,value,unit,note) VALUES (?,?,?,?)');
  ins.run('min_charge', 12, '元', '最低一票收费');
  ins.run('fuel_rate', 0.08, '比例', '燃油附加费，按基础运费计提');
  ins.run('insured_rate', 0.003, '比例', '保价费率（保价额×费率）');
  ins.run('pack_fee', 2, '元', '包装服务费');
  ins.run('overweight_kg', 30, 'kg', '超重阈值');
  ins.run('overweight_fee', 15, '元', '超重附加费');
  ins.run('oversize_cm', 100, 'cm', '超长阈值（单边）');
  ins.run('oversize_fee', 20, '元', '超长附加费');
  ins.run('monthly_discount', 0.95, '比例', '月结协议折扣');
  ins.run('zone_same_city', 0.90, '系数', '同城计费系数');
  ins.run('zone_same_province', 1.00, '系数', '省内计费系数');
  ins.run('zone_region', 1.15, '系数', '同大区（华东）计费系数');
  ins.run('zone_cross', 1.35, '系数', '跨大区计费系数');
  ins.run('zone_remote', 1.80, '系数', '偏远地区计费系数');
  console.log('[db] seeded pricing_rules');
}

// ---- 分拨枢纽（三段码 / 分拨路由） ----
if (cnt('routing_hubs') === 0) {
  const ins = db.prepare('INSERT INTO routing_hubs (code,name,type,province,city,coverage) VALUES (?,?,?,?,?,?)');
  ins.run('HGH', '杭州萧山航空枢纽', 'air', '浙江省', '杭州市', '浙江,上海,江苏,安徽,江西,福建');
  ins.run('SHA', '上海虹桥航空枢纽', 'air', '上海市', '上海市', '上海,江苏,浙江,安徽,山东');
  ins.run('PEK', '北京首都航空枢纽', 'air', '北京市', '北京市', '北京,天津,河北,山西,内蒙古,辽宁,吉林,黑龙江');
  ins.run('CAN', '广州白云航空枢纽', 'air', '广东省', '广州市', '广东,广西,海南,福建,湖南');
  ins.run('CTU', '成都双流航空枢纽', 'air', '四川省', '成都市', '四川,重庆,贵州,云南,西藏');
  ins.run('XIY', '西安咸阳陆运枢纽', 'land', '陕西省', '西安市', '陕西,甘肃,宁夏,青海,新疆');
  console.log('[db] seeded routing_hubs');
}

// ---- 绩效指标权重（direction: 1 正向，-1 逆向） ----
if (cnt('kpi_weights') === 0) {
  const ins = db.prepare('INSERT INTO kpi_weights (metric,label,weight,direction,note) VALUES (?,?,?,?,?)');
  ins.run('pickups', '揽收量', 0.20, 1, '统计期内已揽收任务数');
  ins.run('deliveries', '签收量', 0.30, 1, '统计期内已签收任务数');
  ins.run('on_time', '准时率', 0.35, 1, '承诺时效内签收 ÷ 签收总数');
  ins.run('exception', '异常率', 0.15, -1, '派送异常 ÷ 作业总量（逆向指标）');
  console.log('[db] seeded kpi_weights');
}

// ---- 片区网格（地址区县 → 网格码，分配规则靠它判同片区） ----
if (cnt('grids') === 0) {
  const ins = db.prepare('INSERT INTO grids (grid_code,province,city,district) VALUES (?,?,?,?)');
  [['G-HZ-BJ', '浙江省', '杭州市', '滨江区'],
   ['G-HZ-XH', '浙江省', '杭州市', '西湖区'],
   ['G-HZ-GS', '浙江省', '杭州市', '拱墅区'],
   ['G-SH-PD', '上海市', '上海市', '浦东新区'],
   ['G-SH-XH', '上海市', '上海市', '徐汇区'],
   ['G-BJ-CY', '北京市', '北京市', '朝阳区'],
   ['G-SZ-NS', '广东省', '深圳市', '南山区']].forEach((a) => ins.run(...a));
  console.log('[db] seeded grids');
}

// 分拣操作员（用例参与者，老库补种）
if (!db.prepare("SELECT 1 x FROM employees WHERE role='sorter'").get()) {
  db.prepare('INSERT INTO employees (code,name,role,grid_code) VALUES (?,?,?,?)')
    .run('S6001', '分拣员小吴', 'sorter', null);
  console.log('[db] seeded sorter');
}

// 初始主数据：客户档案与人员。
// 业务数据（订单 / 运单 / 轨迹 / 任务 / 账单 / 异常）一律不预置，全部由实际操作产生。
if (cnt('customers') === 0) {
  const ins = (sql) => db.prepare(sql);
  ins('INSERT INTO customers (name,phone,addr,monthly) VALUES (?,?,?,?)')
    .run('张三', '13800001111', '杭州市滨江区江陵路88号', 0);
  ins('INSERT INTO customers (name,phone,addr,monthly) VALUES (?,?,?,?)')
    .run('李四', '13900002222', '杭州市西湖区文三路100号', 1);
  ins('INSERT INTO customers (name,phone,addr,monthly) VALUES (?,?,?,?)')
    .run('王五', '13700003333', '上海市浦东新区张江路500号', 1);
  ins('INSERT INTO employees (code,name,role,grid_code) VALUES (?,?,?,?)').run('K1001', '快递员小李', 'courier', 'G-HZ-BJ');
  ins('INSERT INTO employees (code,name,role,grid_code) VALUES (?,?,?,?)').run('K1002', '快递员小王', 'courier', 'G-SH-PD');
  ins('INSERT INTO employees (code,name,role,grid_code) VALUES (?,?,?,?)').run('D2001', '调度员老周', 'dispatcher', null);
  ins('INSERT INTO employees (code,name,role,grid_code) VALUES (?,?,?,?)').run('C3001', '理赔专员小陈', 'claims', null);
  ins('INSERT INTO employees (code,name,role,grid_code) VALUES (?,?,?,?)').run('F4001', '财务小赵', 'finance', null);
  ins('INSERT INTO employees (code,name,role,grid_code) VALUES (?,?,?,?)').run('O5001', '运营小孙', 'ops', null);
  console.log('[db] seeded master data (customers/employees)');
} else {
  console.log('[db] exists, skip seed');
}
// ---- 账号：每个角色一个（初始密码统一 123456，登录后可改） ----
if (cnt('users') === 0) {
  const empId = (code) => db.prepare('SELECT id FROM employees WHERE code=?').get(code)?.id ?? null;
  const mk = (username, role, code, name) => {
    const salt = crypto.randomBytes(16).toString('hex');
    const hash = crypto.scryptSync('123456', salt, 32).toString('hex');
    db.prepare('INSERT INTO users (username,pwd_hash,salt,role,employee_id,display_name) VALUES (?,?,?,?,?,?)')
      .run(username, hash, salt, role, empId(code), name);
  };
  mk('admin', 'admin', null, '系统管理员');
  mk('dispatch', 'dispatcher', 'D2001', '调度员老周');
  mk('courier', 'courier', 'K1001', '快递员小李');
  mk('claims', 'claims', 'C3001', '理赔专员小陈');
  mk('finance', 'finance', 'F4001', '财务小赵');
  mk('ops', 'ops', 'O5001', '运营小孙');
  console.log('[db] seeded users（账号 admin/dispatch/courier/claims/finance/ops，初始密码 123456）');
}

// ---- 客户账号：客户登录后只看自己的订单/账单/轨迹 ----
if (!db.prepare("SELECT 1 x FROM users WHERE role='customer'").get()) {
  const custId = (name) => db.prepare('SELECT id FROM customers WHERE name=?').get(name)?.id ?? null;
  const mkCust = (username, name) => {
    const cid = custId(name);
    if (!cid) return;
    const salt = crypto.randomBytes(16).toString('hex');
    const hash = crypto.scryptSync('123456', salt, 32).toString('hex');
    db.prepare('INSERT INTO users (username,pwd_hash,salt,role,customer_id,display_name) VALUES (?,?,?,?,?,?)')
      .run(username, hash, salt, 'customer', cid, name);
  };
  mkCust('zhangsan', '张三');
  mkCust('lisi', '李四');
  mkCust('wangwu', '王五');
  console.log('[db] seeded customer users（zhangsan/lisi/wangwu，初始密码 123456）');
}

console.log('[db] ready');
