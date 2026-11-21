package com.aped.claim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部接口（claims 归 claim-service 独占）：
 * GET /internal/claims[?exception_id=] 供 exception-service 做异常列表 LEFT JOIN
 * 与异常详情的理赔单子列表（原版在单体内直接 JOIN/查询 claims）。
 * 返回按 id 倒序（对应原版详情查询 ORDER BY id DESC；列表 JOIN 对行内序无要求）。
 * 内部接口不做用户角色限制（网关层仍要求登录态），与既有批次口径一致。
 */
@RestController
public class InternalClaimController {

    private final ClaimStore store;

    public InternalClaimController(ClaimStore store) {
        this.store = store;
    }

    @GetMapping("/internal/claims")
    public ResponseEntity<?> list(@RequestParam(name = "exception_id", required = false) Long exceptionId) {
        List<ClaimStore.ClaimRow> rows;
        synchronized (store.lock) {
            rows = exceptionId == null ? new ArrayList<>(store.claims) : store.byException(exceptionId);
        }
        rows.sort((a, b) -> Long.compare(b.id, a.id));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ClaimStore.ClaimRow c : rows) out.add(ClaimJson.claimJson(c));
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }
}
