package com.aped.task;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部接口（批次 5 补）：GET /internal/transit/security-passed?waybill_no=
 * 供 exception-service 定责时判定运单是否已过航空安检——对应原版 claim-engine
 * assessForException 查 transit_logs WHERE security=1；transit_logs 归 task-service 独占。
 * 内部接口不做用户角色限制（网关层仍要求登录态），与既有批次口径一致。
 */
@RestController
public class InternalTransitController {

    private final TaskStore store;

    public InternalTransitController(TaskStore store) {
        this.store = store;
    }

    @GetMapping("/internal/transit/security-passed")
    public ResponseEntity<?> securityPassed(@RequestParam("waybill_no") String waybillNo) {
        boolean passed;
        synchronized (store.lock) {
            passed = store.transitLogs.stream()
                    .anyMatch(t -> waybillNo.equals(t.waybillNo) && t.security == 1);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("waybill_no", waybillNo);
        data.put("security_passed", passed);
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }
}
