// e2e.js — 全流程贯通测试（带断言，非打印演示）
// 前置：后端已启动（默认 http://127.0.0.1:3001 ，可用 APED_BASE 覆盖端口）
//       建议对着一个全新数据库跑：删除 backend/aepds.db 后启动后端即可。
// 覆盖：登录鉴权 → 月结客户下单 → 运费试算(长宽高体积重) → 自动派单 → 接单 →
//       揽收计费 → 中转(真实航班+安检) → 派件 → 签收 → 轨迹 → 月结账单(生成/明细/部分核销/结清) →
//       异常登记 → 定责试算 → 理赔审批赔付（含「已签收运单理赔关单」回归）→ 绩效 → 报表 CSV 导出
const BASE = process.env.APED_BASE ?? 'http://127.0.0.1:3001/api';

let passed = 0;
function assert(cond, label, extra = '') {
  if (!cond) throw new Error(`断言失败：${label} ${extra}`);
  passed += 1;
  console.log(`  ✓ ${label}`);
}

async function req(path, { method = 'GET', body, token, expectFail = false } = {}) {
  const r = await fetch(BASE + path, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await r.text();
  let d;
  try { d = JSON.parse(text); } catch { d = { ok: false, error: text }; }
  if (expectFail) {
    assert(!d.ok, `预期失败且确实失败：${method} ${path}（${d.error ?? r.status}）`);
    return d;
  }
  if (!d.ok) throw new Error(`${method} ${path} -> ${d.error ?? r.status}`);
  return d.data;
}

const login = (u) => req('/auth/login', { method: 'POST', body: { username: u, password: '123456' } });
const period = new Date().toISOString().slice(0, 7);

(async () => {
  console.log('== 0. 健康检查与鉴权 ==');
  const health = await req('/health');
  assert(health.services?.length === 5, '健康检查返回 5 个服务');
  await req('/orders', { expectFail: true }); // 无 token
  await req('/auth/login', { method: 'POST', body: { username: 'admin', password: 'wrong' }, expectFail: true });

  const admin = (await login('admin')).token;
  const finance = (await login('finance')).token;
  const claims = (await login('claims')).token;
  const ops = (await login('ops')).token;
  const courier = (await login('courier')).token;
  assert(admin && finance && claims && ops, 'admin/finance/claims/ops 均可登录');
  await req('/billing/generate', { method: 'POST', token: courier, body: { customer_id: 2, period }, expectFail: true }); // 角色越权 403

  console.log('== 1. 选取月结客户 + 运费试算（长×宽×高体积重） ==');
  const customers = await req('/customers', { token: admin });
  const cust = customers.find((c) => c.monthly === 1);
  assert(cust, '存在月结客户', JSON.stringify(customers.map((c) => c.name)));
  console.log(`  月结客户：${cust.name}（id=${cust.id}）`);

  const quote = await req('/orders/quote', {
    method: 'POST', token: admin,
    body: { product: '次日达', weight: 1, length: 60, width: 50, height: 40, insured: 0, dest_addr: '北京市朝阳区建国路1号', customer_id: cust.id },
  });
  assert(quote.volWeight === 20, '体积重 = 60×50×40÷6000 = 20kg', `实际 ${quote.volWeight}`);
  assert(quote.chargeKg === 20, '计费重取大 = 20kg', `实际 ${quote.chargeKg}`);
  const quoteL = await req('/orders/quote', {
    method: 'POST', token: admin,
    body: { product: '隔日达', weight: 1, volumeL: 12, dest_addr: '北京市朝阳区建国路1号' },
  });
  assert(quoteL.volWeight === 2, 'volumeL 兼容换算：12L×1000÷6000 = 2kg', `实际 ${quoteL.volWeight}`);

  console.log('== 2. 下单 → 自动派单 → 接单 → 揽收计费 ==');
  const order = await req('/orders', {
    method: 'POST', token: admin,
    body: {
      customer_id: cust.id, sender: cust.name, recv_name: '收件人甲', recv_phone: '13911112222',
      recv_addr: '北京市朝阳区建国路1号', category: '电子产品', weight: 3, slot: '10:00-12:00', product: '次日达',
    },
  });
  assert(order.order_no && order.est_fee > 0, `下单成功 ${order.order_no}，预估运费 ¥${order.est_fee}`);

  const assigned = await req('/pickup/assign', { method: 'POST', token: admin, body: { order_ids: [order.id] } });
  assert(assigned.assigned === 1, '自动派单成功 1 单', JSON.stringify(assigned));
  console.log(`  指派：${assigned.assignments[0].courier}（${assigned.assignments[0].reason}）`);

  const tasks = await req('/pickup/tasks', { token: admin });
  const task = tasks.find((t) => t.order_no === order.order_no);
  assert(task && task.status === '待接单', '揽收任务为待接单');
  await req('/pickup/accept', { method: 'POST', token: admin, body: { id: task.id } });

  const picked = await req('/pickup', {
    method: 'POST', token: admin,
    body: { task_id: task.id, weight: 3.2, length: 60, width: 50, height: 40, insured: 500, product: '次日达' },
  });
  assert(picked.waybill_no, `运单生成 ${picked.waybill_no}`);
  assert(picked.chargeKg === 20, '揽收计费重 = 体积重 20kg（取大）', `实际 ${picked.chargeKg}`);
  assert(picked.fee > 0 && Array.isArray(picked.items) && picked.items.length >= 2, `计费明细完整，运费 ¥${picked.fee}`);
  const wb1 = picked.waybill_no;
  const fee1 = picked.fee;

  console.log('== 3. 分拣中转（真实航班 + 安检） ==');
  await req('/transit', {
    method: 'POST', token: admin, expectFail: true,
    body: { waybill_no: wb1, node: '杭州萧山货站', flight: 'CA1701' },
  }); // CA1701 不在航班主数据，必须被拒
  const transit = await req('/transit', {
    method: 'POST', token: admin,
    body: { waybill_no: wb1, node: '杭州萧山货站', next_hop: '北京首都货站', flight: 'CA1832', security: true },
  });
  assert(transit.id > 0 && transit.route, '中转登记成功（CA1832，已过安检，含路由建议）');

  console.log('== 4. 派件 → 签收 → 轨迹 ==');
  const dAssign = await req('/delivery/assign', { method: 'POST', token: admin, body: { waybill_nos: [wb1] } });
  assert(dAssign.assigned === 1, '派件任务自动分配 1 单');
  const dTasks = await req('/delivery/tasks', { token: admin });
  const dTask = dTasks.find((t) => t.waybill_no === wb1);
  assert(dTask, '派件任务存在');
  await req('/delivery/sign', { method: 'POST', token: admin, body: { id: dTask.id, signer: '收件人甲', sign_way: '本人签收' } });
  const track1 = await req('/tracking/' + wb1, { token: admin });
  assert(track1.waybill.status === '已签收', '运单已签收');
  assert(track1.events.length >= 5, `全程轨迹 ${track1.events.length} 条（≥5）`);
  assert(track1.events.some((e) => e.desc_text.includes('安检')), '轨迹含安检记录（安检落库）');

  console.log('== 5. 月结账单：生成 → 明细 → 部分核销 → 结清 ==');
  let bill;
  try {
    bill = await req('/billing/generate', { method: 'POST', token: finance, body: { customer_id: cust.id, period } });
  } catch (e) {
    if (!String(e.message).includes('已生成')) throw e;
    const bills = await req('/billing', { token: finance });
    const found = bills.find((b) => b.customer_id === cust.id && b.period === period);
    assert(found, '账单已存在（重跑场景），复用之');
    bill = { bill_id: found.id, bill_no: found.bill_no, amount: found.amount, item_count: found.item_count };
  }
  assert(bill.amount >= fee1, `账单 ${bill.bill_no} 金额 ¥${bill.amount} ≥ 本票运费 ¥${fee1}`);
  const detail = await req('/billing/' + bill.bill_id, { token: finance });
  assert(detail.items.some((i) => i.waybill_no === wb1), '账单明细含本票运单');
  if (detail.bill.status !== '已结清') {
    const part = await req('/billing/settle', { method: 'POST', token: finance, body: { id: bill.bill_id, amount: 10, voucher: 'E2E-TEST-1' } });
    assert(part.status === '部分核销', '部分核销 10 元 → 部分核销', JSON.stringify(part));
    const done = await req('/billing/settle', { method: 'POST', token: finance, body: { id: bill.bill_id } });
    assert(done.status === '已结清' && done.remaining === 0, '剩余全额核销 → 已结清');
    const detail2 = await req('/billing/' + bill.bill_id, { token: finance });
    assert(detail2.receipts.length >= 2, `核销流水 ${detail2.receipts.length} 条（≥2）`);
  } else {
    assert(detail.receipts.length >= 1, '账单已结清且有核销流水（重跑场景）');
  }

  console.log('== 6. 第二票：签收前异常 → 定责试算 → 理赔审批赔付 ==');
  const order2 = await req('/orders', {
    method: 'POST', token: admin,
    body: {
      customer_id: cust.id, sender: cust.name, recv_name: '收件人乙', recv_phone: '13933334444',
      recv_addr: '上海市浦东新区张江路500号', category: '服装', weight: 2, slot: '14:00-16:00', product: '隔日达',
    },
  });
  await req('/pickup/assign', { method: 'POST', token: admin, body: { order_ids: [order2.id] } });
  const task2 = (await req('/pickup/tasks', { token: admin })).find((t) => t.order_no === order2.order_no);
  await req('/pickup/accept', { method: 'POST', token: admin, body: { id: task2.id } });
  const picked2 = await req('/pickup', {
    method: 'POST', token: admin,
    body: { task_id: task2.id, weight: 2, length: 30, width: 20, height: 10, insured: 1000, product: '隔日达' },
  });
  const wb2 = picked2.waybill_no;
  await req('/transit', {
    method: 'POST', token: admin,
    body: { waybill_no: wb2, node: '杭州萧山货站', next_hop: '上海虹桥货站', flight: 'MU5101', security: true },
  });
  await req('/delivery/assign', { method: 'POST', token: admin, body: { waybill_nos: [wb2] } });

  const ex = await req('/exceptions', {
    method: 'POST', token: claims,
    body: { waybill_no: wb2, type: '破损', desc: '外包装破损，内件受损', photos: 'evidence://damage-1.jpg' },
  });
  assert(ex.id > 0, `异常登记成功 #${ex.id}`);
  const assess = await req(`/exceptions/${ex.id}/assess`, { token: claims });
  assert(assess.liable === 'carrier', '定责：承运方责任', assess.liable_label);
  assert(assess.rules.some((r) => r.includes('安检')), '定责依据引用安检记录（security 落库判定）', JSON.stringify(assess.rules));
  assert(assess.amount === 400, '试算：保价 1000 × 中度 0.4 = ¥400', `实际 ${assess.amount}`);

  const claim = await req('/claims', { method: 'POST', token: claims, body: { exception_id: ex.id, severity: '中度' } });
  assert(claim.amount === 400, `理赔单核定 ¥${claim.amount}`);
  const approved = await req('/claims/approve', { method: 'POST', token: claims, body: { id: claim.claim_id, approved: true, by: '理赔专员小陈' } });
  assert(approved.status === '已赔付', '理赔审批通过并赔付');
  const track2 = await req('/tracking/' + wb2, { token: admin });
  assert(track2.waybill.status === '已关闭', '理赔后运单已关闭');

  console.log('== 7. 回归：已签收运单发起理赔并关单（旧状态机曾回滚） ==');
  const ex1 = await req('/exceptions', {
    method: 'POST', token: claims,
    body: { waybill_no: wb1, type: '破损', desc: '签收后发现内件破损', photos: 'evidence://damage-2.jpg' },
  });
  const claim1 = await req('/claims', { method: 'POST', token: claims, body: { exception_id: ex1.id, severity: '轻微' } });
  assert(claim1.amount === 100, `已签收票理赔核定：保价 500 × 轻微 0.2 = ¥${claim1.amount}`);
  const approved1 = await req('/claims/approve', { method: 'POST', token: claims, body: { id: claim1.claim_id, approved: true, by: '理赔专员小陈' } });
  assert(approved1.status === '已赔付', '已签收运单理赔审批成功（不回滚）');
  const track1b = await req('/tracking/' + wb1, { token: admin });
  assert(track1b.waybill.status === '已关闭', '已签收运单理赔后转已关闭');

  console.log('== 8. 绩效与报表导出 ==');
  const perf = await req('/performance?period=' + period, { token: ops });
  assert(Array.isArray(perf.rows) && perf.rows.length >= 1, `绩效返回 ${perf.rows.length} 名快递员`);
  assert(Array.isArray(perf.weights) && perf.weights.length === 4, '绩效权重 4 项齐全');
  const wsum = perf.weights.reduce((s, w) => s + w.weight, 0);
  assert(Math.abs(wsum - 1) < 0.01, `权重和 ≈ 1（实际 ${wsum.toFixed(3)}）`);
  const totalPickups = perf.rows.reduce((s, r) => s + (r.pickups ?? 0), 0);
  assert(totalPickups >= 2, `绩效揽收量合计 ${totalPickups}（≥2，两票按同片区优先分给不同快递员）`);

  const summary = await req('/reports/summary?period=' + period, { token: ops });
  assert(summary.waybills >= 2, `运营汇总运单量 ${summary.waybills}（≥2）`);

  const csvPerf = await fetch(`${BASE}/reports/export?type=performance&period=${period}&token=${encodeURIComponent(ops)}`).then((r) => r.text());
  assert(csvPerf.includes('排名') && csvPerf.trim().split('\n').length >= 2, '绩效 CSV 导出含表头与数据行');
  const csvWb = await fetch(`${BASE}/reports/export?type=waybills&period=${period}&token=${encodeURIComponent(ops)}`).then((r) => r.text());
  assert(csvWb.includes(wb1) && csvWb.includes(wb2), '运单 CSV 导出含两票运单');

  console.log(`\nE2E PASS（断言 ${passed} 条全部通过）`);
})().catch((e) => { console.error('E2E FAIL:', e.message); process.exit(1); });
