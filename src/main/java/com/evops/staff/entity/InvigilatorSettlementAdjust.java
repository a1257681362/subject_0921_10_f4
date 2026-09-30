package com.evops.staff.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结算调整单：结算单确认锁定后的唯一改账通道，只追加、不改原明细。
 * SUPPLEMENT 补发 / DEDUCT 扣减；金额累加到结算单 adjust_amount / total_amount。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_invigilator_settlement_adjust")
public class InvigilatorSettlementAdjust extends BaseEntity {
    private Long statementId;
    private String period;
    private Long invigilatorId;
    private String adjustNo;
    /** 关联聘任（整单补发/扣减可空）。 */
    private Long appointmentId;
    /** SUPPLEMENT / DEDUCT。 */
    private String adjustType;
    /** 正数金额，方向由 adjustType 决定。 */
    private BigDecimal amount;
    private String reason;
    /** APPLIED 已入账。 */
    private String status;
    private String operator;
    private LocalDateTime appliedAt;
}
