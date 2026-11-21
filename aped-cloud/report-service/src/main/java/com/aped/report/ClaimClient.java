package com.aped.report;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * report → claim 只读聚合：理赔单全量（claims 归 claim-service 独占），
 * 供运营汇总的已赔付统计与异常数据集的 LEFT JOIN。
 */
@FeignClient(name = "claim-service")
public interface ClaimClient {

    @GetMapping("/internal/claims")
    Map<String, Object> listAll();
}
