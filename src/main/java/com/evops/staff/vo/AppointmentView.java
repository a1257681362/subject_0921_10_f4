package com.evops.staff.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 聘任记录视图：主表字段 + 监考员 / 批次 / 考场冗余信息，供人事与本人核对。
 */
@Data
public class AppointmentView {
    private Long id;
    private String appointmentNo;
    private Long batchId;
    private String batchCode;
    private String batchName;
    private LocalDate examDate;
    private Long roomId;
    private String roomNo;
    private String slotType;
    private String roleCode;
    private String roleName;
    private String currentVersionNo;
    private Integer rowVersion;
    private Long invigilatorId;
    private String staffNo;
    private String invigilatorName;
    private String organization;
    private String source;
    private String status;
    private String payStatus;
    private Long stipendVersionId;
    private String stipendVersionNo;
    private BigDecimal rateSnapshot;
    private BigDecimal extraSnapshot;
    private BigDecimal amount;
    private Integer sessionIndexInBatch;
    private Long settledInStatementId;
    private String settlementStatus;
    private LocalDateTime checkedInAt;
    private String checkedInBy;
    private String absentMarkedBy;
    private LocalDateTime absentMarkedAt;
    private String operator;
    private LocalDateTime createTime;
}
