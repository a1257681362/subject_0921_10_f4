package com.evops.staff.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.Map;

/**
 * 按批次排班生成聘任（两步：preview → execute）。
 * assignments 为 roomId → 监考员工号 的派班表；不传则只开槽位、待人事后续派班。
 */
@Data
public class AppointmentGenerateRequest {
    @NotNull(message = "批次 id 不能为空")
    private Long batchId;
    /** key=考场 id，value=监考员工号。 */
    private Map<Long, String> assignments;
}
