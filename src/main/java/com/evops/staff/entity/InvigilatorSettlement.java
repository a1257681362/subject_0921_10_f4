package com.evops.staff.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 监考费结算单：一人一月一条。
 * DRAFT 可重复出单覆盖；CONFIRMED 确认后锁定，要改走调整单。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_invigilator_settlement")
public class InvigilatorSettlement extends BaseEntity {
    private String statementNo;
    /** 归属月份 yyyyMM。 */
    private String period;
    private Long invigilatorId;
    private String staffNo;
    private String name;
    private String organization;
    /** DRAFT / CONFIRMED。 */
    private String status;
    /** 场次计酬合计（不含调整）。 */
    private BigDecimal sessionAmount;
    /** 调整合计（补发为正、扣减为负）。 */
    private BigDecimal adjustAmount;
    /** 应付合计 = 场次 + 调整。 */
    private BigDecimal totalAmount;
    private Integer sessionCount;
    private Integer adjustCount;
    private String generatedBy;
    private LocalDateTime generatedAt;
    private String confirmedBy;
    private LocalDateTime confirmedAt;
    private String remark;
}
