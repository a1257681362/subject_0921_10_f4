package com.evops.staff.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 月底一键出结算单预检 / 执行汇总：按人列单（新建 / 草稿重建 / 已锁定跳过）。
 */
@Data
public class SettlementGeneratePreview {
    private String period;
    private int staffCount;
    private int createCount;
    private int rebuildCount;
    private int lockedSkipCount;
    private int sessionCount;
    private BigDecimal totalAmount = BigDecimal.ZERO;
    private String summary;
    private List<PersonSheet> sheets = new ArrayList<>();

    @Data
    public static class PersonSheet {
        private Long statementId;
        private Long invigilatorId;
        private String staffNo;
        private String name;
        private String organization;
        /** CREATE 新建 / REBUILD 草稿重建 / LOCKED 已确认锁定跳过（要改走调整单）。 */
        private String action;
        private int sessionCount;
        private BigDecimal sessionAmount;
        private boolean actionable;
        private String note;
    }
}
