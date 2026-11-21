package com.aped.billing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部接口（批次 6 补，bills/bill_items 归 billing-service 独占）：
 * GET /internal/bills 返回账单原行（不做客户 JOIN，客户名由消费方拼装），
 * 供 report-service 的运营汇总与月结账单数据集聚合。插入序 = id 升序。
 * 内部接口不做用户角色限制（网关层仍要求登录态），与既有批次口径一致。
 */
@RestController
public class InternalDataController {

    private final BillingStore store;

    public InternalDataController(BillingStore store) {
        this.store = store;
    }

    @GetMapping("/internal/bills")
    public ResponseEntity<?> bills() {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store.lock) {
            for (BillingStore.Bill b : store.bills) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", b.id);
                m.put("bill_no", b.billNo);
                m.put("customer_id", b.customerId);
                m.put("period", b.period);
                m.put("amount", BillingStore.num(b.amount));
                m.put("item_count", b.itemCount);
                m.put("status", b.status);
                m.put("created_at", b.createdAt);
                m.put("paid_amount", BillingStore.num(b.paidAmount));
                m.put("receipt_count", b.receiptCount);
                out.add(m);
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }
}
