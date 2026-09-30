package com.evops.staff.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 排班生成聘任预检：列出将新建 / 保留 / 取消的槽位与派班方案，不落库。
 */
@Data
public class AppointmentGenPreview {
    private Long batchId;
    private String batchCode;
    private int roomCount;
    private int createCount;
    private int keepCount;
    private int cancelCount;
    private int unassignedCount;
    private String summary;
    private List<SlotPlan> items = new ArrayList<>();

    @Data
    public static class SlotPlan {
        /** CREATE 新建 / KEEP 已存在保留 / CANCEL 场次取消聘任。 */
        private String action;
        private Long roomId;
        private String roomNo;
        private String roleCode;
        private String staffNo;
        private String staffName;
        /** 该监考员本次将承担的第几场（超场补贴参考）。 */
        private Integer sessionIndex;
        private boolean actionable;
        private String note;
    }
}
