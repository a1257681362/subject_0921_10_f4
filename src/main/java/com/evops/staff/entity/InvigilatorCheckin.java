package com.evops.staff.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 场次签到：一个聘任槽位一条（唯一约束防重复签到重复计酬）。
 * PRESENT/LATE 到场计酬；ABSENT 缺勤不计费。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_invigilator_checkin")
public class InvigilatorCheckin extends BaseEntity {
    private Long appointmentId;
    private Long batchId;
    private Long roomId;
    private Long invigilatorId;
    private String roleCode;
    /** PRESENT / LATE / ABSENT。 */
    private String result;
    private LocalDateTime checkinAt;
    private String operator;
    private String note;
}
