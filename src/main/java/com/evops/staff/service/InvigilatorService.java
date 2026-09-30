package com.evops.staff.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.staff.common.StaffConstants;
import com.evops.staff.dto.InvigilatorRequest;
import com.evops.staff.entity.Invigilator;
import com.evops.staff.mapper.InvigilatorMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 监考人员花名册：建档、停用、按工号查询；可担任角色以 JSON 落库。
 */
@Service
public class InvigilatorService {

    private final InvigilatorMapper invigilatorMapper;
    private final ObjectMapper objectMapper;

    public InvigilatorService(InvigilatorMapper invigilatorMapper, ObjectMapper objectMapper) {
        this.invigilatorMapper = invigilatorMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Invigilator create(InvigilatorRequest request) {
        List<String> roles = normalizeRoles(request.getAvailableRoles());
        Invigilator staff = new Invigilator();
        staff.setStaffNo(request.getStaffNo().trim());
        staff.setName(request.getName().trim());
        staff.setPhone(trimToNull(request.getPhone()));
        staff.setIdNo(trimToNull(request.getIdNo()));
        staff.setOrganization(trimToNull(request.getOrganization()));
        staff.setAvailableRoles(writeRoles(roles));
        staff.setStatus(StaffConstants.STAFF_ENABLED);
        staff.setRemark(request.getRemark());
        try {
            invigilatorMapper.insert(staff);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("DUPLICATE_STAFF_NO", "监考员工号已存在：" + staff.getStaffNo());
        }
        return staff;
    }

    @Transactional(readOnly = true)
    public List<Invigilator> list(String status, String keyword) {
        QueryWrapper<Invigilator> wrapper = new QueryWrapper<Invigilator>()
                .orderByAsc("staff_no");
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq("status", status.trim());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.and(w -> w.like("name", keyword.trim()).or().like("staff_no", keyword.trim()));
        }
        return invigilatorMapper.selectList(wrapper);
    }

    public Invigilator get(Long id) {
        Invigilator staff = invigilatorMapper.selectById(id);
        if (staff == null) {
            throw new BusinessException("INVIGILATOR_NOT_FOUND", "监考人员不存在：id=" + id);
        }
        return staff;
    }

    public Invigilator getByStaffNo(String staffNo) {
        if (staffNo == null || staffNo.trim().isEmpty()) {
            throw new BusinessException("STAFF_NO_REQUIRED", "监考员工号不能为空");
        }
        Invigilator staff = invigilatorMapper.selectOne(new QueryWrapper<Invigilator>()
                .eq("staff_no", staffNo.trim()));
        if (staff == null) {
            throw new BusinessException("INVIGILATOR_NOT_FOUND", "监考人员不存在：工号 " + staffNo.trim());
        }
        return staff;
    }

    /** 校验人员可担任指定角色（停用人员 / 角色不在可担任清单均拒收）。 */
    public Invigilator requireEnabledForRole(String staffNo, String roleCode) {
        Invigilator staff = getByStaffNo(staffNo);
        if (!StaffConstants.STAFF_ENABLED.equals(staff.getStatus())) {
            throw new BusinessException("INVIGILATOR_DISABLED",
                    "监考员已停用，不能排班：" + staff.getStaffNo() + " " + staff.getName());
        }
        List<String> roles = readRoles(staff.getAvailableRoles());
        if (!roles.contains(roleCode)) {
            throw new BusinessException("ROLE_NOT_AVAILABLE",
                    String.format("监考员 %s（%s）不可担任角色 %s，其可担任角色：%s",
                            staff.getStaffNo(), staff.getName(), roleCode, roles));
        }
        return staff;
    }

    public List<String> readRoles(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<String> roles = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return roles == null ? new ArrayList<>() : roles;
        } catch (Exception e) {
            throw new BusinessException("ROLES_BROKEN", "监考员可担任角色无法解析：" + e.getMessage());
        }
    }

    private List<String> normalizeRoles(List<String> raw) {
        List<String> roles = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            roles.add(StaffConstants.ROLE_INVIGILATOR);
            return roles;
        }
        for (String r : raw) {
            if (r == null || r.trim().isEmpty()) {
                continue;
            }
            String code = r.trim().toUpperCase();
            if (!roles.contains(code)) {
                roles.add(code);
            }
        }
        if (roles.isEmpty()) {
            roles.add(StaffConstants.ROLE_INVIGILATOR);
        }
        return roles;
    }

    private String writeRoles(List<String> roles) {
        try {
            return objectMapper.writeValueAsString(roles);
        } catch (Exception e) {
            throw new BusinessException("ROLES_SERIALIZE_FAILED", "角色清单序列化失败：" + e.getMessage());
        }
    }

    private String trimToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
