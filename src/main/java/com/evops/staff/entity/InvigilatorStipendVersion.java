package com.evops.staff.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 角色津贴标准版本：DRAFT → ACTIVE → DISABLED，发布即冻结标准金额。
 * 同模块同时刻仅一版 ACTIVE；聘任计酬时把金额快照进聘任行，标准以后调整不改历史口径。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_invigilator_stipend_version")
public class InvigilatorStipendVersion extends BaseEntity {
    private Long stipendId;
    private String versionNo;
    /** DRAFT / ACTIVE / DISABLED */
    private String status;
    /** 单场津贴（元）。 */
    private BigDecimal ratePerSession;
    /** 每多监一场的超场补贴（可空）。 */
    private BigDecimal extraPerSession;
    private LocalDate effectiveFrom;
    private String remark;
    private String publishedBy;
    private LocalDateTime publishedAt;
    private String disabledBy;
    private LocalDateTime disabledAt;
    private String operator;
}
