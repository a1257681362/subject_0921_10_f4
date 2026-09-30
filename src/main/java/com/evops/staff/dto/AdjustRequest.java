package com.evops.staff.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 结算调整：仅 CONFIRMED 锁定单可受理；补发 / 扣减都只追加，不改原场次明细。
 */
@Data
public class AdjustRequest {
    @NotBlank(message = "调整类型不能为空：SUPPLEMENT 补发 / DEDUCT 扣减")
    private String adjustType;
    @NotNull(message = "调整金额不能为空")
    @DecimalMin(value = "0.01", message = "调整金额必须大于 0")
    private BigDecimal amount;
    @NotBlank(message = "调整原因不能为空")
    private String reason;
    /** 关联聘任（整单性补发 / 扣减可空）。 */
    private Long appointmentId;
}
