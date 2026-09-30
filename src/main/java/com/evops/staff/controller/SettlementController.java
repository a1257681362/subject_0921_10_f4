package com.evops.staff.controller;

import com.evops.common.ApiResponse;
import com.evops.staff.dto.AdjustRequest;
import com.evops.staff.dto.SettlementGenerateRequest;
import com.evops.staff.entity.InvigilatorSettlement;
import com.evops.staff.entity.InvigilatorSettlementAdjust;
import com.evops.staff.service.SettlementService;
import com.evops.staff.vo.SettlementGeneratePreview;
import com.evops.staff.vo.SettlementView;
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
 * 监考费结算单：
 * - 月底一键出单（/generate 两步），按批次考试日期归月；
 * - 人事看全量（不带 X-Staff-No）；本人带头只出 / 只看自己的单子；
 * - 确认后锁定（/confirm），要改走调整单（/adjusts，只追加）。
 */
@RestController
@RequestMapping("/api/settlements")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /** 出单第一步：按人列将新建 / 草稿重建 / 已锁定跳过，不落库。本人带头只预估本人。 */
    @PostMapping("/generate/preview")
    public ApiResponse<SettlementGeneratePreview> previewGenerate(
            @Valid @RequestBody SettlementGenerateRequest request,
            @RequestHeader(value = "X-Staff-No", required = false) String staffNo) {
        return ApiResponse.ok(settlementService.previewGenerate(request.getPeriod(), staffNo));
    }

    /** 出单第二步：一人一月一条草稿结算单（草稿可重复出单重建；锁定单跳过）。 */
    @PostMapping("/generate")
    public ApiResponse<SettlementGeneratePreview> generate(
            @Valid @RequestBody SettlementGenerateRequest request,
            @RequestHeader(value = "X-Staff-No", required = false) String staffNo) {
        return ApiResponse.ok(settlementService.generate(request.getPeriod(), staffNo));
    }

    /** 人事确认结算单：DRAFT → CONFIRMED，确认后锁定。 */
    @PostMapping("/{id}/confirm")
    public ApiResponse<InvigilatorSettlement> confirm(@PathVariable Long id) {
        return ApiResponse.ok(settlementService.confirm(id));
    }

    /** 锁定后调整：补发 / 扣减只追加，实时累加应付合计。 */
    @PostMapping("/{id}/adjusts")
    public ApiResponse<InvigilatorSettlementAdjust> adjust(@PathVariable Long id,
                                                            @Valid @RequestBody AdjustRequest request) {
        return ApiResponse.ok(settlementService.adjust(id, request));
    }

    /** 结算单列表：人事全量；本人带头只列自己。 */
    @GetMapping
    public ApiResponse<List<InvigilatorSettlement>> list(@RequestParam(required = false) String period,
                                                         @RequestParam(required = false) String status,
                                                         @RequestHeader(value = "X-Staff-No", required = false)
                                                         String headerStaffNo,
                                                         @RequestParam(value = "staffNo", required = false)
                                                         String paramStaffNo) {
        String staffNo = headerStaffNo != null ? headerStaffNo : paramStaffNo;
        return ApiResponse.ok(settlementService.list(period, status, staffNo));
    }

    /** 结算单详情（场次明细 + 调整明细）；本人带头只能看自己的单子。 */
    @GetMapping("/{id}")
    public ApiResponse<SettlementView> get(@PathVariable Long id,
                                           @RequestHeader(value = "X-Staff-No", required = false)
                                           String headerStaffNo,
                                           @RequestParam(value = "staffNo", required = false)
                                           String paramStaffNo) {
        String staffNo = headerStaffNo != null ? headerStaffNo : paramStaffNo;
        return ApiResponse.ok(settlementService.getView(id, staffNo));
    }
}
