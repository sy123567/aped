package com.aped.exception;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * exception → claim 同步调用：异常列表/详情需要 JOIN 理赔单
 * （原版 claim.js 在单体内直接 LEFT JOIN claims；claims 归 claim-service 独占）。
 */
@FeignClient(name = "claim-service")
public interface ClaimClient {

    /** 全部理赔单（异常列表 JOIN 用）。 */
    @GetMapping("/internal/claims")
    Map<String, Object> listAll();

    /** 某异常单的理赔单（异常详情用，下游按 id 倒序）。 */
    @GetMapping("/internal/claims")
    Map<String, Object> listByException(@RequestParam("exception_id") long exceptionId);
}
