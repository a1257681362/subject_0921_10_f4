package com.evops.staff.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class StipendVersionRequest {
    @NotNull(message = "单场津贴标准不能为空")
    @DecimalMin(value = "0", message = "单场津贴不能为负")
    private BigDecimal ratePerSession;
    /** 每多监一场加发；为空表示不设超场补贴。 */
    @DecimalMin(value = "0", message = "超场补贴不能为负")
    private BigDecimal extraPerSession;
    private LocalDate effectiveFrom;
    private String remark;
}
