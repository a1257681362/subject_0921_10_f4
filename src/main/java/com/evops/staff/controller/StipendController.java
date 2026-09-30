package com.evops.staff.controller;

import com.evops.common.ApiResponse;
import com.evops.staff.dto.StipendRequest;
import com.evops.staff.dto.StipendVersionRequest;
import com.evops.staff.entity.InvigilatorStipend;
import com.evops.staff.entity.InvigilatorStipendVersion;
import com.evops.staff.service.StipendService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 角色津贴标准模块（模块 + 版本 + 状态）：角色不同标准不同，版本发布即冻结。
 */
@RestController
@RequestMapping("/api/invigilator-stipends")
public class StipendController {

    private final StipendService stipendService;

    public StipendController(StipendService stipendService) {
        this.stipendService = stipendService;
    }

    @PostMapping
    public ApiResponse<InvigilatorStipend> create(@Valid @RequestBody StipendRequest request) {
        return ApiResponse.ok(stipendService.create(request));
    }

    @GetMapping
    public ApiResponse<List<InvigilatorStipend>> list(@RequestParam(required = false) String projectCode,
                                                      @RequestParam(required = false) String roleCode) {
        return ApiResponse.ok(stipendService.list(projectCode, roleCode));
    }

    @GetMapping("/{stipendId}")
    public ApiResponse<InvigilatorStipend> get(@PathVariable Long stipendId) {
        return ApiResponse.ok(stipendService.get(stipendId));
    }

    @PostMapping("/{stipendId}/versions")
    public ApiResponse<InvigilatorStipendVersion> createVersion(
            @PathVariable Long stipendId,
            @Valid @RequestBody StipendVersionRequest request) {
        return ApiResponse.ok(stipendService.createDraftVersion(stipendId, request));
    }

    @GetMapping("/{stipendId}/versions")
    public ApiResponse<List<InvigilatorStipendVersion>> listVersions(@PathVariable Long stipendId) {
        return ApiResponse.ok(stipendService.listVersions(stipendId));
    }

    @GetMapping("/versions/{versionId}")
    public ApiResponse<InvigilatorStipendVersion> getVersion(@PathVariable Long versionId) {
        return ApiResponse.ok(stipendService.getVersion(versionId));
    }

    /** 发布：DRAFT → ACTIVE，旧生效版自动 DISABLED。 */
    @PostMapping("/versions/{versionId}/publish")
    public ApiResponse<InvigilatorStipendVersion> publish(@PathVariable Long versionId) {
        return ApiResponse.ok(stipendService.publish(versionId));
    }

    @PostMapping("/versions/{versionId}/disable")
    public ApiResponse<InvigilatorStipendVersion> disable(@PathVariable Long versionId) {
        return ApiResponse.ok(stipendService.disable(versionId));
    }
}
