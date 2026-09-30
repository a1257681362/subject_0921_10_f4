package com.evops.staff.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class StipendRequest {
    @NotBlank(message = "标准编码不能为空")
    private String code;
    @NotBlank(message = "标准名称不能为空")
    private String name;
    /** 项目编码，缺省 DEFAULT。 */
    private String projectCode;
    @NotBlank(message = "角色码不能为空")
    private String roleCode;
    private String remark;
}
