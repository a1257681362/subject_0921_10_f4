package com.evops.staff.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 结算单生成：指定归属月份 yyyyMM（按批次考试日期归月）。
 */
@Data
public class SettlementGenerateRequest {
    @NotBlank(message = "结算归属月份不能为空，格式 yyyyMM")
    private String period;
}
