package com.evops.staff.controller;

import com.evops.common.ApiResponse;
import com.evops.staff.dto.AppointmentGenerateRequest;
import com.evops.staff.dto.AppointmentManualRequest;
import com.evops.staff.dto.CheckinRequest;
import com.evops.staff.dto.ReplaceRequest;
import com.evops.staff.entity.InvigilatorAppointment;
import com.evops.staff.entity.InvigilatorAppointmentVersion;
import com.evops.staff.service.AppointmentService;
import com.evops.staff.vo.AppointmentGenPreview;
import com.evops.staff.vo.AppointmentView;
import com.evops.staff.vo.StaffActionPreview;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 聘任记录模块（聘任记录 + 版本 + 状态）。
 * - 监考随排班生成聘任（/generate 两步）；人事手工补派考点级角色（/manual）；
 * - 换人 / 取消 / 撤销签到均追加版本；
 * - 场次签到两步动作，缺勤不计费。
 * 本人视角：请求头 X-Staff-No 带本人工号时只返回本人记录；不带为人事全量。
 */
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    /** 排班生成聘任第一步：看新建 / 保留 / 取消槽位与派班方案，不落库。 */
    @PostMapping("/generate/preview")
    public ApiResponse<AppointmentGenPreview> previewGenerate(
            @Valid @RequestBody AppointmentGenerateRequest request) {
        return ApiResponse.ok(appointmentService.previewGenerate(
                request.getBatchId(), request.getAssignments()));
    }

    /** 排班生成聘任第二步：确认后同步聘任槽位（重排自动冲销多余待执行槽位）。 */
    @PostMapping("/generate")
    public ApiResponse<AppointmentGenPreview> generate(
            @Valid @RequestBody AppointmentGenerateRequest request) {
        return ApiResponse.ok(appointmentService.generate(
                request.getBatchId(), request.getAssignments()));
    }

    /** 人事手工补聘：主考 / 巡考等考点级角色（roomId 留空），或指定考场监考员。 */
    @PostMapping("/manual")
    public ApiResponse<InvigilatorAppointment> manualAppoint(
            @Valid @RequestBody AppointmentManualRequest request) {
        return ApiResponse.ok(appointmentService.manualAppoint(request));
    }

    /** 换人：留替换记录（REPLACE 版本），已签到计酬 / 已进结算单的不能换。 */
    @PostMapping("/{id}/replace")
    public ApiResponse<InvigilatorAppointment> replace(@PathVariable Long id,
                                                       @Valid @RequestBody ReplaceRequest request) {
        return ApiResponse.ok(appointmentService.replace(id, request.getStaffNo(), request.getReason()));
    }

    /** 取消聘任（CANCEL 版本）。 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<InvigilatorAppointment> cancel(@PathVariable Long id,
                                                      @RequestParam(required = false) String reason) {
        return ApiResponse.ok(appointmentService.cancel(id, reason));
    }

    /** 撤销误签：未进结算单方可撤销，回退待执行并留 REVOKE 版本。 */
    @PostMapping("/{id}/revoke-checkin")
    public ApiResponse<InvigilatorAppointment> revokeCheckin(@PathVariable Long id,
                                                             @RequestParam(required = false) String reason) {
        return ApiResponse.ok(appointmentService.revokeCheckin(id, reason));
    }

    /** 场次签到第一步：逐条列到场计酬金额 / 缺勤不计费 / 跳过原因，不落库。 */
    @PostMapping("/checkin/preview")
    public ApiResponse<StaffActionPreview> previewCheckin(@Valid @RequestBody CheckinRequest request) {
        return ApiResponse.ok(appointmentService.previewCheckin(request));
    }

    /** 场次签到第二步：到场按角色标准计津贴，缺勤金额 0 不计费。 */
    @PostMapping("/checkin")
    public ApiResponse<StaffActionPreview> checkin(@Valid @RequestBody CheckinRequest request) {
        return ApiResponse.ok(appointmentService.executeCheckin(request));
    }

    /**
     * 聘任查询：人事看全量（不带 X-Staff-No）；本人带头只看自己。
     */
    @GetMapping
    public ApiResponse<List<AppointmentView>> list(@RequestParam(required = false) Long batchId,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String payStatus,
                                                   @RequestHeader(value = "X-Staff-No", required = false)
                                                   String headerStaffNo,
                                                   @RequestParam(value = "staffNo", required = false)
                                                   String paramStaffNo) {
        String staffNo = headerStaffNo != null ? headerStaffNo : paramStaffNo;
        return ApiResponse.ok(appointmentService.listViews(batchId, staffNo, status, payStatus));
    }

    @GetMapping("/{id}")
    public ApiResponse<AppointmentView> get(@PathVariable Long id) {
        return ApiResponse.ok(appointmentService.getView(id));
    }

    /** 聘任记录版本链：APPOINT → REPLACE / CANCEL / REVOKE 全程留痕。 */
    @GetMapping("/{id}/versions")
    public ApiResponse<List<InvigilatorAppointmentVersion>> versions(@PathVariable Long id) {
        return ApiResponse.ok(appointmentService.listVersions(id));
    }
}
