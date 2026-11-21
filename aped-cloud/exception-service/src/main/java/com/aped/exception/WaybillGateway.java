package com.aped.exception;

import feign.FeignException;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * exception → billing 的运单访问封装（waybills 归 billing 独占）：
 * 读运单行与按状态机推进状态，错误口径与 task-service 的同名封装一致——
 * 运单不存在 → null / 下游 error 文案；业务拒绝（400）→ 原样透传文案；
 * 服务不可用 → Down（调用方转 503 降级响应）。
 */
@Component
public class WaybillGateway {

    /** 下游服务不可用（区别于业务拒绝）。 */
    static final class Down extends RuntimeException {
        Down(String name) { super(name); }
    }

    private final BillingClient billingClient;

    public WaybillGateway(BillingClient billingClient) {
        this.billingClient = billingClient;
    }

    /** 取运单整行：不存在返回 null；服务异常抛 Down。 */
    public Map<String, Object> fetch(String no) {
        Map<String, Object> resp;
        try {
            resp = billingClient.getWaybill(no);
        } catch (FeignException e) {
            if (e.status() == 404) return null;
            throw new Down("运单服务");
        } catch (Exception e) {
            throw new Down("运单服务");
        }
        Map<String, Object> data = ExJson.dataMap(resp);
        if (data == null) throw new Down("运单服务");
        return data;
    }

    /**
     * 按运单状态机推进状态（对应 helpers.setWaybillStatus）：
     * 成功返回 null；业务拒绝返回下游 error 文案（调用方按原版 biz 口径转 400）；
     * 服务异常抛 Down。
     */
    public String pushStatus(String no, String to) {
        try {
            billingClient.setWaybillStatus(no, Map.of("to", to));
            return null;
        } catch (FeignException e) {
            String msg = ExJson.feignError(e);
            if (e.status() == 400 && msg != null) return msg;
            if (e.status() == 404) return msg == null ? "运单不存在" : msg;
            throw new Down("运单服务");
        } catch (Exception e) {
            throw new Down("运单服务");
        }
    }
}
