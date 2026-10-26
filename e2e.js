// e2e.js — 全流程贯通测试
const BASE = 'http://127.0.0.1:3001/api';
const j = async (p, m, b) => {
  const r = await fetch(BASE + p, {
    method: m ?? 'GET',
    headers: b ? { 'Content-Type': 'application/json' } : undefined,
    body: b ? JSON.stringify(b) : undefined,
  });
  const d = await r.json();
  if (!d.ok) throw new Error(p + ' -> ' + d.error);
  return d.data;
};
(async () => {
  const orders = await j('/orders');
  const o = orders.find((x) => x.status === '待派单');
  console.log('1 选中待派单订单:', o.order_no);

  await j('/pickup/assign', 'POST', { order_ids: [o.id], courier_id: 1 });
  const tasks = await j('/pickup/tasks');
  const t = tasks.find((x) => x.order_no === o.order_no);
  console.log('2 已派单，任务状态:', t.status);

  await j('/pickup/accept', 'POST', { id: t.id });
  console.log('3 快递员已接单');

  const p = await j('/pickup', 'POST', { task_id: t.id, weight: 3.2, volumeL: 9000, insured: 500, product: '次日达' });
  console.log('4 揽收+计费: 运单', p.waybill_no, '运费 ¥' + p.fee, '|', p.detail);

  await j('/transit', 'POST', { waybill_no: p.waybill_no, node: '杭州萧山货站', next_hop: '北京首都货站', flight: 'CA1701', operator_id: 1 });
  console.log('5 分拣中转登记完成');

  await j('/delivery/assign', 'POST', { waybill_nos: [p.waybill_no], courier_id: 1 });
  const dts = await j('/delivery/tasks');
  const d = dts.find((x) => x.waybill_no === p.waybill_no);
  await j('/delivery/sign', 'POST', { id: d.id, signer: '收件人B', sign_way: '本人签收' });
  console.log('6 派件签收完成');

  const tr = await j('/tracking/' + p.waybill_no);
  console.log('7 轨迹条数:', tr.events.length, '| 最终状态:', tr.waybill.status);

  await j('/billing/generate', 'POST', { customer_id: 1, period: new Date().toISOString().slice(0, 7) });
  const bills = await j('/billing');
  console.log('8 账单:', bills.length, '张 | 最新:', bills[0].bill_no, '¥' + bills[0].amount);

  const ex = await j('/exceptions', 'POST', { waybill_no: p.waybill_no, type: '破损', desc: '外包装破损' });
  await j('/claims', 'POST', { exception_id: ex.id, amount: 50 });
  const claims = await j('/exceptions');
  console.log('9 异常+理赔已登记:', claims[0].type, claims[0].claim_status);

  const perf = await j('/performance');
  console.log('10 绩效排名:', perf.rows.map((r) => `${r.rank}.${r.courier}(${r.score}分)`).join(' '));
  const csv = await fetch(BASE + '/reports/export?type=performance').then((r) => r.text());
  console.log('11 CSV 导出行数:', csv.trim().split('\n').length);
  console.log('E2E PASS');
})().catch((e) => { console.error('E2E FAIL:', e.message); process.exit(1); });
