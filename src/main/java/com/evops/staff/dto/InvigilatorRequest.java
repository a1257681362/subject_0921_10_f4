package com.evops.staff.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.util.List;

@Data
public class InvigilatorRequest {
    @NotBlank(message = "工号不能为空")
    private String staffNo;
    @NotBlank(message = "姓名不能为空")
    private String name;
    private String phone;
    private String idNo;
    private String organization;
    /** 可担任角色码清单；为空默认仅可任监考员 INVIGILATOR。 */
    private List<String> availableRoles;
    private String remark;
}
