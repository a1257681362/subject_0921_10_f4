package com.evops.staff.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色津贴标准模块：按 项目+角色 建档，一个模块挂多个标准版本。
 * 角色不同标准不同（主考 / 监考员 / 巡考 / 备用各一套）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_invigilator_stipend")
public class InvigilatorStipend extends BaseEntity {
    private String code;
    private String name;
    /** 项目编码：与批次 project_code 对齐。 */
    private String projectCode;
    /** 监考角色码。 */
    private String roleCode;
    private String remark;
}
