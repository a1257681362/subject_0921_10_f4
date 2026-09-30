package com.evops.staff.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 聘任记录（当前态）：一个批次一个考场/角色槽位一条。
 * 随排班自动生成（也可人工补聘）；换人 / 签到 / 缺勤在本表反映当前态，全程在版本表留痕。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_invigilator_appointment")
public class InvigilatorAppointment extends BaseEntity {
    /** 业务流水号（整条聘任共用）。 */
    private String appointmentNo;
    private Long batchId;
    /** 考场级槽位必填；考点级（主考/巡考）可空。 */
    private Long roomId;
    /** ROOM 考场级 / SITE 考点级。 */
    private String slotType;
    private String roleCode;
    /** 当前版本号。 */
    private String currentVersionNo;
    /** 当前被聘监考员（取消后置空）。 */
    private Long invigilatorId;
    /** AUTO 随排班生成 / MANUAL 人工补聘。 */
    private String source;
    /** PENDING / CHECKED_IN / ABSENT / CANCELLED。 */
    private String status;
    /** NONE 暂不计酬 / CALCULATED 已计津贴。 */
    private String payStatus;
    /** 计酬标准版本快照。 */
    private Long stipendVersionId;
    private String stipendVersionNo;
    private BigDecimal rateSnapshot;
    private BigDecimal extraSnapshot;
    /** 实测金额（缺勤/取消为 0）。 */
    private BigDecimal amount;
    /** 本人在本批次的计费场次序号（超场补贴用）。 */
    private Integer sessionIndexInBatch;
    /** 已进入的结算单 id。 */
    private Long settledInStatementId;
    private LocalDateTime checkedInAt;
    private String checkedInBy;
    private String absentMarkedBy;
    private LocalDateTime absentMarkedAt;
    private Integer rowVersion;
    private String operator;
}
