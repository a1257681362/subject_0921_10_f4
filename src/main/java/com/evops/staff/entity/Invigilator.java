package com.evops.staff.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 监考人员（兼职老师花名册）。工号全局唯一；停用后历史聘任保留。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_invigilator")
public class Invigilator extends BaseEntity {
    private String staffNo;
    private String name;
    private String phone;
    private String idNo;
    /** 所在单位 / 学校。 */
    private String organization;
    /** 可担任角色清单 JSON：["CHIEF_INVIGILATOR","INVIGILATOR","PATROL"]。 */
    private String availableRoles;
    /** ENABLED 可排班 / DISABLED 停用。 */
    private String status;
    private String remark;
}
