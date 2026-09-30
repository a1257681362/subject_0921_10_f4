package com.evops.staff.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 聘任记录版本（只追加）：
 * v1 初聘 APPOINT；换人 REPLACE（记 from/to）；取消 CANCEL。
 * 每次变更把当时状态、金额、标准版本快照一并冻结，月底对账可逐条核对。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_invigilator_appointment_version")
public class InvigilatorAppointmentVersion extends BaseEntity {
    private Long appointmentId;
    private String appointmentNo;
    private Integer versionSeq;
    private String versionNo;
    /** APPOINT / REPLACE / CANCEL。 */
    private String changeType;
    /** 版本对应聘任状态。 */
    private String status;
    private Long invigilatorId;
    /** 换人前任。 */
    private Long fromInvigilatorId;
    /** 换人新任。 */
    private Long toInvigilatorId;
    private String roleCode;
    private Long stipendVersionId;
    private String stipendVersionNo;
    private BigDecimal rateSnapshot;
    private BigDecimal extraSnapshot;
    private String payStatus;
    private BigDecimal amount;
    private String reason;
    private String operator;
    private LocalDateTime changedAt;
}
