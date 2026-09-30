package com.evops.staff.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 场次签到批量动作（两步：preview → execute）。
 * 结果 PRESENT/LATE 到场计津贴，ABSENT 缺勤不计费。
 */
@Data
public class CheckinRequest {

    @Valid
    @NotEmpty(message = "签到列表不能为空")
    private List<CheckinItem> items;

    @Data
    public static class CheckinItem {
        @NotNull(message = "聘任记录 id 不能为空")
        private Long appointmentId;
        /** PRESENT / LATE / ABSENT，缺省 PRESENT。 */
        private String result;
        private String note;
    }
}
