package com.evops.staff.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 结算单明细：一行一场聘任计酬，出单时快照。
 * 结算单锁定后聘任侧任何变动都不影响本明细（差异走调整单）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_invigilator_settlement_item")
public class InvigilatorSettlementItem extends BaseEntity {
    private Long statementId;
    private String period;
    private Long invigilatorId;
    private Long appointmentId;
    private String appointmentNo;
    private String appointmentVersionNo;
    private Long batchId;
    private String batchCode;
    private String batchName;
    private LocalDate examDate;
    private Long roomId;
    private String roomNo;
    private String roleCode;
    private String stipendVersionNo;
    private BigDecimal rateSnapshot;
    private Integer sessionIndexInBatch;
    /** SESSION 场次计酬。 */
    private String itemType;
    private BigDecimal amount;
    private String remark;
}
