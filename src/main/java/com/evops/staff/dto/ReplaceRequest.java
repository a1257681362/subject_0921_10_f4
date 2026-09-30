package com.evops.staff.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 换人：PENDING / ABSENT 的聘任可换；已签到计酬的须先撤销签到（且未进结算单）。
 */
@Data
public class ReplaceRequest {
    /** 新任监考员工号。 */
    @NotBlank(message = "新任监考员工号不能为空")
    private String staffNo;
    @NotBlank(message = "换人原因不能为空")
    private String reason;
}
