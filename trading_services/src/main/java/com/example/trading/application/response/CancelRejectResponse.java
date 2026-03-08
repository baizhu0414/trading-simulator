package com.example.trading.application.response;

import com.example.trading.common.enums.ErrorCodeEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 撤单拒绝响应（对齐任务书3.8规范）
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class CancelRejectResponse extends BaseResponse {
    /** 撤单请求唯一编号 */
    private String clOrderId;
    /** 待撤原订单唯一编号 */
    private String origClOrderId;
    /** 拒绝错误码 */
    private Integer rejectCode;
    /** 拒绝原因说明 */
    private String rejectText;

    public static CancelRejectResponse build(ErrorCodeEnum errorCode) {
        CancelRejectResponse response = new CancelRejectResponse();
        response.setCode(errorCode.getCode().toString());
        response.setMsg(errorCode.getDesc());
        response.setRejectCode(errorCode.getCode());
        response.setRejectText(errorCode.getDesc());
        return response;
    }

    public static CancelRejectResponse build(String clOrderId, String origClOrderId, ErrorCodeEnum errorCode) {
        CancelRejectResponse response = build(errorCode);
        response.setClOrderId(clOrderId);
        response.setOrigClOrderId(origClOrderId);
        return response;
    }
}