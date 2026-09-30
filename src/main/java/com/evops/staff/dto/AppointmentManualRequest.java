package com.evops.staff.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 人事手工补聘 / 指派：指定批次、槽位（考场可空=考点级角色）与监考员工号。
 */
@Data
public class AppointmentManualRequest {
    @NotNull(message = "批次 id 不能为空")
    private Long batchId;
    /** 考场 id；主考 / 巡考等考点级角色留空。 */
    private Long roomId;
    @NotBlank(message = "角色码不能为空")
    private String roleCode;
    /** 监考员工号；为空表示只开槽位待后续换人 / 指派。 */
    private String staffNo;
    private String remark;
}
