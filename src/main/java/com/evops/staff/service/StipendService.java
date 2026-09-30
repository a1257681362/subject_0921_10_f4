package com.evops.staff.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.staff.common.StaffConstants;
import com.evops.staff.dto.StipendRequest;
import com.evops.staff.dto.StipendVersionRequest;
import com.evops.staff.entity.InvigilatorStipend;
import com.evops.staff.entity.InvigilatorStipendVersion;
import com.evops.staff.mapper.InvigilatorStipendMapper;
import com.evops.staff.mapper.InvigilatorStipendVersionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色津贴标准模块：
 * - 标准按 项目+角色 建档（角色不同标准不同），标准下挂多个版本；
 * - 版本 DRAFT → ACTIVE → DISABLED：发布即冻结金额，自动停用旧生效版；
 * - 聘任计酬只认当前 ACTIVE 版本，并把金额快照进聘任行，标准以后调整不改历史口径。
 */
@Service
public class StipendService {

    /** 角色码 → 中文名（对账展示用）。 */
    private static final Map<String, String> ROLE_NAMES = new HashMap<>();

    static {
        ROLE_NAMES.put(StaffConstants.ROLE_CHIEF, "主考");
        ROLE_NAMES.put(StaffConstants.ROLE_INVIGILATOR, "监考员");
        ROLE_NAMES.put(StaffConstants.ROLE_PATROL, "巡考");
        ROLE_NAMES.put(StaffConstants.ROLE_BACKUP, "备用");
    }

    private final InvigilatorStipendMapper stipendMapper;
    private final InvigilatorStipendVersionMapper versionMapper;

    public StipendService(InvigilatorStipendMapper stipendMapper,
                          InvigilatorStipendVersionMapper versionMapper) {
        this.stipendMapper = stipendMapper;
        this.versionMapper = versionMapper;
    }

    public static String roleName(String roleCode) {
        return ROLE_NAMES.getOrDefault(roleCode, roleCode);
    }

    // ---------- 标准模块 ----------

    @Transactional
    public InvigilatorStipend create(StipendRequest request) {
        String project = normalizeProject(request.getProjectCode());
        String role = normalizeRole(request.getRoleCode());
        InvigilatorStipend stipend = new InvigilatorStipend();
        stipend.setCode(request.getCode().trim());
        stipend.setName(request.getName().trim());
        stipend.setProjectCode(project);
        stipend.setRoleCode(role);
        stipend.setRemark(request.getRemark());
        try {
            stipendMapper.insert(stipend);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("DUPLICATE_STIPEND",
                    "标准编码已存在，或项目 " + project + " 下角色 " + role + " 已建档");
        }
        return stipend;
    }

    @Transactional(readOnly = true)
    public List<InvigilatorStipend> list(String projectCode, String roleCode) {
        QueryWrapper<InvigilatorStipend> wrapper = new QueryWrapper<InvigilatorStipend>()
                .orderByAsc("project_code", "role_code");
        if (projectCode != null && !projectCode.trim().isEmpty()) {
            wrapper.eq("project_code", projectCode.trim().toUpperCase());
        }
        if (roleCode != null && !roleCode.trim().isEmpty()) {
            wrapper.eq("role_code", roleCode.trim().toUpperCase());
        }
        return stipendMapper.selectList(wrapper);
    }

    public InvigilatorStipend get(Long stipendId) {
        InvigilatorStipend stipend = stipendMapper.selectById(stipendId);
        if (stipend == null) {
            throw new BusinessException("STIPEND_NOT_FOUND", "津贴标准不存在：id=" + stipendId);
        }
        return stipend;
    }

    // ---------- 标准版本 ----------

    @Transactional
    public InvigilatorStipendVersion createDraftVersion(Long stipendId, StipendVersionRequest request) {
        InvigilatorStipend stipend = get(stipendId);
        InvigilatorStipendVersion version = new InvigilatorStipendVersion();
        version.setStipendId(stipend.getId());
        version.setVersionNo(nextVersionNo(stipend));
        version.setStatus(StaffConstants.VERSION_DRAFT);
        version.setRatePerSession(request.getRatePerSession());
        version.setExtraPerSession(request.getExtraPerSession());
        version.setEffectiveFrom(request.getEffectiveFrom());
        version.setRemark(request.getRemark());
        version.setOperator(OperatorHolder.current());
        versionMapper.insert(version);
        return version;
    }

    /** 发布：DRAFT → ACTIVE；同模块旧 ACTIVE 自动置 DISABLED。 */
    @Transactional
    public InvigilatorStipendVersion publish(Long versionId) {
        InvigilatorStipendVersion version = requireVersion(versionId);
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        if (StaffConstants.VERSION_ACTIVE.equals(version.getStatus())) {
            throw new BusinessException("STIPEND_VERSION_ALREADY_ACTIVE",
                    "版本已是生效状态，无需重复发布：" + version.getVersionNo());
        }
        if (StaffConstants.VERSION_DISABLED.equals(version.getStatus())) {
            throw new BusinessException("STIPEND_VERSION_DISABLED",
                    "停用版本不可重新发布，请新建版本：" + version.getVersionNo());
        }

        List<InvigilatorStipendVersion> actives = versionMapper.selectList(
                new QueryWrapper<InvigilatorStipendVersion>()
                        .eq("stipend_id", version.getStipendId())
                        .eq("status", StaffConstants.VERSION_ACTIVE));
        for (InvigilatorStipendVersion active : actives) {
            active.setStatus(StaffConstants.VERSION_DISABLED);
            active.setDisabledBy(operator);
            active.setDisabledAt(now);
            versionMapper.updateById(active);
        }

        version.setStatus(StaffConstants.VERSION_ACTIVE);
        version.setPublishedBy(operator);
        version.setPublishedAt(now);
        versionMapper.updateById(version);
        return version;
    }

    @Transactional
    public InvigilatorStipendVersion disable(Long versionId) {
        InvigilatorStipendVersion version = requireVersion(versionId);
        if (!StaffConstants.VERSION_ACTIVE.equals(version.getStatus())) {
            throw new BusinessException("STIPEND_VERSION_NOT_ACTIVE",
                    "仅生效版本可停用：" + version.getVersionNo());
        }
        version.setStatus(StaffConstants.VERSION_DISABLED);
        version.setDisabledBy(OperatorHolder.current());
        version.setDisabledAt(LocalDateTime.now());
        versionMapper.updateById(version);
        return version;
    }

    @Transactional(readOnly = true)
    public List<InvigilatorStipendVersion> listVersions(Long stipendId) {
        get(stipendId);
        return versionMapper.selectList(new QueryWrapper<InvigilatorStipendVersion>()
                .eq("stipend_id", stipendId)
                .orderByDesc("id"));
    }

    public InvigilatorStipendVersion getVersion(Long versionId) {
        return requireVersion(versionId);
    }

    /**
     * 取项目+角色当前生效标准版本：精确匹配项目；项目未建档时不做隐式兜底
     * （津贴口径必须显式发布，避免错发），无生效版抛业务异常。
     * 注意：本方法故意不加 @Transactional——编排等外层事务会 catch 此处
     * "标准未配置"异常后继续，加事务切面会把共享事务误标 rollback-only。
     */
    public InvigilatorStipendVersion requireActiveVersion(String projectCode, String roleCode) {
        String project = normalizeProject(projectCode);
        String role = normalizeRole(roleCode);
        InvigilatorStipend stipend = stipendMapper.selectOne(new QueryWrapper<InvigilatorStipend>()
                .eq("project_code", project)
                .eq("role_code", role));
        if (stipend == null) {
            throw new BusinessException("STIPEND_NOT_CONFIGURED",
                    "项目 " + project + " 的角色 " + roleName(role) + "（" + role
                            + "）尚未建立津贴标准，请先建档并发布生效版本");
        }
        List<InvigilatorStipendVersion> actives = versionMapper.selectList(
                new QueryWrapper<InvigilatorStipendVersion>()
                        .eq("stipend_id", stipend.getId())
                        .eq("status", StaffConstants.VERSION_ACTIVE)
                        .orderByDesc("id"));
        if (actives.isEmpty()) {
            throw new BusinessException("STIPEND_VERSION_NOT_ACTIVE",
                    "项目 " + project + " 的角色 " + roleName(role)
                            + " 津贴标准还没有生效版本，请先发布");
        }
        if (actives.size() > 1) {
            throw new BusinessException("MULTIPLE_ACTIVE_STIPEND_VERSION",
                    "项目 " + project + " 角色 " + role + " 存在多个生效标准版本，请先停用多余版本");
        }
        return actives.get(0);
    }

    // ---------- 内部 ----------

    private InvigilatorStipendVersion requireVersion(Long versionId) {
        if (versionId == null) {
            throw new BusinessException("STIPEND_VERSION_ID_REQUIRED", "标准版本 id 不能为空");
        }
        InvigilatorStipendVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException("STIPEND_VERSION_NOT_FOUND", "津贴标准版本不存在：id=" + versionId);
        }
        return version;
    }

    /** 版本号：标准 code + -S + 递增序号（如 JK-DEFAULT-INV-S1）。 */
    private String nextVersionNo(InvigilatorStipend stipend) {
        int max = 0;
        String prefix = stipend.getCode() + "-S";
        for (InvigilatorStipendVersion v : versionMapper.selectList(
                new QueryWrapper<InvigilatorStipendVersion>().eq("stipend_id", stipend.getId()))) {
            String no = v.getVersionNo();
            if (no != null && no.startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // 非本标准序号忽略
                }
            }
        }
        return prefix + (max + 1);
    }

    private String normalizeProject(String projectCode) {
        if (projectCode == null || projectCode.trim().isEmpty()) {
            return StaffConstants.PROJECT_DEFAULT;
        }
        return projectCode.trim().toUpperCase();
    }

    private String normalizeRole(String roleCode) {
        if (roleCode == null || roleCode.trim().isEmpty()) {
            throw new BusinessException("ROLE_REQUIRED", "角色码不能为空");
        }
        return roleCode.trim().toUpperCase();
    }
}
