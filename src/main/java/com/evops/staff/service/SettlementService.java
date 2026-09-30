package com.evops.staff.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.entity.ExamRoom;
import com.evops.exam.mapper.ExamRoomMapper;
import com.evops.exam.service.ExamBatchService;
import com.evops.staff.common.StaffConstants;
import com.evops.staff.dto.AdjustRequest;
import com.evops.staff.entity.Invigilator;
import com.evops.staff.entity.InvigilatorAppointment;
import com.evops.staff.entity.InvigilatorSettlement;
import com.evops.staff.entity.InvigilatorSettlementAdjust;
import com.evops.staff.entity.InvigilatorSettlementItem;
import com.evops.staff.mapper.InvigilatorAppointmentMapper;
import com.evops.staff.mapper.InvigilatorSettlementAdjustMapper;
import com.evops.staff.mapper.InvigilatorSettlementItemMapper;
import com.evops.staff.mapper.InvigilatorSettlementMapper;
import com.evops.staff.vo.SettlementGeneratePreview;
import com.evops.staff.vo.SettlementView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监考费结算：
 * - 月底一键出单：按批次考试日期归月（yyyyMM），把已签到计酬（CALCULATED）的聘任按人汇总，
 *   一人一月一条结算单，明细逐场快照；
 * - 草稿可重复出单覆盖；确认后锁定，聘任侧冻结，要改只能走调整单（只追加）；
 * - 人事看全量、本人凭工号只看自己；调整单实时累加进应付合计。
 */
@Service
public class SettlementService {

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final InvigilatorSettlementMapper settlementMapper;
    private final InvigilatorSettlementItemMapper itemMapper;
    private final InvigilatorSettlementAdjustMapper adjustMapper;
    private final InvigilatorAppointmentMapper appointmentMapper;
    private final InvigilatorService invigilatorService;
    private final ExamBatchService batchService;
    private final ExamRoomMapper roomMapper;

    public SettlementService(InvigilatorSettlementMapper settlementMapper,
                             InvigilatorSettlementItemMapper itemMapper,
                             InvigilatorSettlementAdjustMapper adjustMapper,
                             InvigilatorAppointmentMapper appointmentMapper,
                             InvigilatorService invigilatorService,
                             ExamBatchService batchService,
                             ExamRoomMapper roomMapper) {
        this.settlementMapper = settlementMapper;
        this.itemMapper = itemMapper;
        this.adjustMapper = adjustMapper;
        this.appointmentMapper = appointmentMapper;
        this.invigilatorService = invigilatorService;
        this.batchService = batchService;
        this.roomMapper = roomMapper;
    }

    // ---------- 月底一键出单（两步：预检 → 执行） ----------

    @Transactional(readOnly = true)
    public SettlementGeneratePreview previewGenerate(String period, String staffNo) {
        String p = normalizePeriod(period);
        Map<Long, List<InvigilatorAppointment>> byStaff = billableByStaff(p);
        Map<Long, InvigilatorSettlement> existingByStaff = existingByStaff(p);

        SettlementGeneratePreview preview = new SettlementGeneratePreview();
        preview.setPeriod(p);

        int createCount = 0;
        int rebuildCount = 0;
        int lockedSkip = 0;
        int sessionTotal = 0;
        BigDecimal amountTotal = BigDecimal.ZERO;

        List<Long> staffIds = new ArrayList<>(byStaff.keySet());
        for (Map.Entry<Long, InvigilatorSettlement> e : existingByStaff.entrySet()) {
            if (!staffIds.contains(e.getKey())) {
                staffIds.add(e.getKey());
            }
        }

        for (Long staffId : staffIds) {
            Invigilator staff = invigilatorService.get(staffId);
            if (staffNo != null && !staffNo.trim().isEmpty()
                    && !staff.getStaffNo().equals(staffNo.trim())) {
                continue;
            }
            List<InvigilatorAppointment> sessions = byStaff.getOrDefault(staffId, new ArrayList<>());
            InvigilatorSettlement existed = existingByStaff.get(staffId);

            // 本月无计费场次：没有单子不建；有草稿 / 锁定单则展示其现状
            if (sessions.isEmpty() && existed == null) {
                continue;
            }

            BigDecimal sessionAmount = sumAmount(sessions);
            SettlementGeneratePreview.PersonSheet sheet = new SettlementGeneratePreview.PersonSheet();
            sheet.setInvigilatorId(staffId);
            sheet.setStaffNo(staff.getStaffNo());
            sheet.setName(staff.getName());
            sheet.setOrganization(staff.getOrganization());
            sheet.setSessionCount(sessions.size());
            sheet.setSessionAmount(sessionAmount);

            if (existed == null) {
                sheet.setStatementId(null);
                sheet.setAction("CREATE");
                sheet.setActionable(sessions.size() > 0);
                sheet.setNote(sessions.size() > 0
                        ? "将新建草稿结算单，含 " + sessions.size() + " 场，合计 " + sessionAmount + " 元"
                        : "本月无计费场次");
                createCount++;
            } else if (StaffConstants.SETTLE_CONFIRMED.equals(existed.getStatus())) {
                sheet.setStatementId(existed.getId());
                sheet.setAction("LOCKED");
                sheet.setActionable(false);
                sheet.setNote("结算单 " + existed.getStatementNo()
                        + " 已确认锁定，出单跳过；更正请走调整单");
                lockedSkip++;
            } else {
                sheet.setStatementId(existed.getId());
                sheet.setAction("REBUILD");
                sheet.setActionable(true);
                sheet.setNote("草稿单 " + existed.getStatementNo() + " 将按最新计酬重建明细（"
                        + sessions.size() + " 场，" + sessionAmount + " 元），调整不适用草稿");
                rebuildCount++;
            }
            preview.getSheets().add(sheet);
            sessionTotal += sessions.size();
            amountTotal = amountTotal.add(sessionAmount);
        }

        preview.setStaffCount(preview.getSheets().size());
        preview.setCreateCount(createCount);
        preview.setRebuildCount(rebuildCount);
        preview.setLockedSkipCount(lockedSkip);
        preview.setSessionCount(sessionTotal);
        preview.setTotalAmount(amountTotal);
        preview.setSummary(String.format(
                "%s 月结算：覆盖 %d 人、%d 场计酬，场次金额合计 %s 元；新建 %d 单、草稿重建 %d 单、已锁定跳过 %d 单",
                p, preview.getStaffCount(), sessionTotal, amountTotal,
                createCount, rebuildCount, lockedSkip));
        return preview;
    }

    @Transactional
    public SettlementGeneratePreview generate(String period, String staffNo) {
        String p = normalizePeriod(period);
        // 先按执行前口径预检（CREATE/REBUILD/LOCKED），执行后原样返回，动作统计不被二次出单改变
        SettlementGeneratePreview before = previewGenerate(p, staffNo);
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        for (SettlementGeneratePreview.PersonSheet sheet : before.getSheets()) {
            if ("LOCKED".equals(sheet.getAction())) {
                continue;
            }
            List<InvigilatorAppointment> sessions = billableByStaff(p)
                    .getOrDefault(sheet.getInvigilatorId(), new ArrayList<>());
            Invigilator staff = invigilatorService.get(sheet.getInvigilatorId());

            InvigilatorSettlement statement;
            boolean isNew = sheet.getStatementId() == null;
            if (isNew) {
                statement = new InvigilatorSettlement();
                statement.setStatementNo(nextStatementNo(p));
                statement.setPeriod(p);
                statement.setInvigilatorId(staff.getId());
            } else {
                statement = settlementMapper.selectById(sheet.getStatementId());
                // 草稿重建：先清旧明细并解除聘任挂单标记
                clearItemsAndUnbind(statement.getId());
            }
            statement.setStaffNo(staff.getStaffNo());
            statement.setName(staff.getName());
            statement.setOrganization(staff.getOrganization());
            statement.setStatus(StaffConstants.SETTLE_DRAFT);
            statement.setSessionAmount(sumAmount(sessions));
            statement.setSessionCount(sessions.size());
            // 草稿阶段没有调整；调整合计沿用上一轮（草稿不允许调整，通常为 0）
            BigDecimal adjustAmount = recalcAdjustAmount(statement.getId());
            statement.setAdjustAmount(adjustAmount);
            statement.setAdjustCount(countAdjusts(statement.getId()));
            statement.setTotalAmount(statement.getSessionAmount().add(adjustAmount));
            statement.setGeneratedBy(operator);
            statement.setGeneratedAt(now);
            if (isNew) {
                settlementMapper.insert(statement);
            } else {
                settlementMapper.updateById(statement);
            }

            insertItems(statement, sessions);
            bindAppointments(sessions, statement.getId());
            // 回填实际生成 / 重建的结算单 id，便于调用方继续确认
            sheet.setStatementId(statement.getId());
        }
        return before;
    }

    // ---------- 确认锁定 ----------

    @Transactional
    public InvigilatorSettlement confirm(Long statementId) {
        InvigilatorSettlement statement = requireStatement(statementId);
        if (StaffConstants.SETTLE_CONFIRMED.equals(statement.getStatus())) {
            throw new BusinessException("SETTLEMENT_ALREADY_CONFIRMED",
                    "结算单已确认锁定，无需重复确认：" + statement.getStatementNo());
        }
        // 明细数与场次金额以库里明细重算，防止草稿数据漂移
        List<InvigilatorSettlementItem> items = itemMapper.selectList(
                new QueryWrapper<InvigilatorSettlementItem>()
                        .eq("statement_id", statementId)
                        .eq("item_type", StaffConstants.ITEM_SESSION)
                        .orderByAsc("exam_date", "id"));
        BigDecimal sessionAmount = BigDecimal.ZERO;
        for (InvigilatorSettlementItem item : items) {
            sessionAmount = sessionAmount.add(item.getAmount());
        }
        statement.setSessionAmount(sessionAmount);
        statement.setSessionCount(items.size());
        statement.setStatus(StaffConstants.SETTLE_CONFIRMED);
        statement.setConfirmedBy(OperatorHolder.current());
        statement.setConfirmedAt(LocalDateTime.now());
        settlementMapper.updateById(statement);
        return statement;
    }

    // ---------- 调整（锁定后唯一改账通道，只追加） ----------

    @Transactional
    public InvigilatorSettlementAdjust adjust(Long statementId, AdjustRequest request) {
        InvigilatorSettlement statement = requireStatement(statementId);
        if (!StaffConstants.SETTLE_CONFIRMED.equals(statement.getStatus())) {
            throw new BusinessException("SETTLEMENT_NOT_CONFIRMED",
                    "仅已确认锁定的结算单可登记调整；草稿单请直接重新出单：" + statement.getStatementNo());
        }
        String type = request.getAdjustType().trim().toUpperCase();
        if (!StaffConstants.ADJUST_SUPPLEMENT.equals(type)
                && !StaffConstants.ADJUST_DEDUCT.equals(type)) {
            throw new BusinessException("ADJUST_TYPE_INVALID",
                    "调整类型仅支持 SUPPLEMENT 补发 / DEDUCT 扣减");
        }
        if (request.getAppointmentId() != null) {
            InvigilatorAppointment appt = appointmentMapper.selectById(request.getAppointmentId());
            if (appt == null || !statement.getInvigilatorId().equals(appt.getInvigilatorId())) {
                throw new BusinessException("ADJUST_APPOINTMENT_MISMATCH",
                        "关联聘任不存在或不属于本结算单监考员");
            }
        }

        InvigilatorSettlementAdjust adjust = new InvigilatorSettlementAdjust();
        adjust.setStatementId(statement.getId());
        adjust.setPeriod(statement.getPeriod());
        adjust.setInvigilatorId(statement.getInvigilatorId());
        adjust.setAdjustNo(nextAdjustNo(statement));
        adjust.setAppointmentId(request.getAppointmentId());
        adjust.setAdjustType(type);
        adjust.setAmount(request.getAmount().setScale(2, BigDecimal.ROUND_HALF_UP));
        adjust.setReason(request.getReason().trim());
        adjust.setStatus("APPLIED");
        adjust.setOperator(OperatorHolder.current());
        adjust.setAppliedAt(LocalDateTime.now());
        adjustMapper.insert(adjust);

        statement.setAdjustAmount(recalcAdjustAmount(statement.getId()));
        statement.setAdjustCount(countAdjusts(statement.getId()));
        statement.setTotalAmount(statement.getSessionAmount().add(statement.getAdjustAmount()));
        settlementMapper.updateById(statement);
        return adjust;
    }

    // ---------- 查询：人事全量 / 本人只看自己 ----------

    @Transactional(readOnly = true)
    public List<InvigilatorSettlement> list(String period, String status, String staffNo) {
        QueryWrapper<InvigilatorSettlement> wrapper = new QueryWrapper<InvigilatorSettlement>()
                .orderByDesc("period").orderByAsc("staff_no");
        if (period != null && !period.trim().isEmpty()) {
            wrapper.eq("period", normalizePeriod(period.trim()));
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq("status", status.trim().toUpperCase());
        }
        if (staffNo != null && !staffNo.trim().isEmpty()) {
            wrapper.eq("staff_no", staffNo.trim());
        }
        return settlementMapper.selectList(wrapper);
    }

    /** 结算单详情。本人视角传 requiredStaffNo：只允许看自己的单子。 */
    @Transactional(readOnly = true)
    public SettlementView getView(Long statementId, String requiredStaffNo) {
        InvigilatorSettlement statement = requireStatement(statementId);
        if (requiredStaffNo != null && !requiredStaffNo.trim().isEmpty()
                && !requiredStaffNo.trim().equals(statement.getStaffNo())) {
            // 不泄露他人单据是否存在，统一按无权限处理
            throw new BusinessException("SETTLEMENT_FORBIDDEN",
                    "只能查看本人的结算单：statementId=" + statementId);
        }
        SettlementView view = new SettlementView();
        view.setStatement(statement);
        view.setItems(itemMapper.selectList(new QueryWrapper<InvigilatorSettlementItem>()
                .eq("statement_id", statementId)
                .orderByAsc("item_type", "exam_date", "id")));
        view.setAdjusts(adjustMapper.selectList(new QueryWrapper<InvigilatorSettlementAdjust>()
                .eq("statement_id", statementId)
                .orderByAsc("applied_at", "id")));
        return view;
    }

    // ---------- 内部 ----------

    /** 本月已签到计酬的聘任，按监考员分组（缺勤 NONE / 取消的不计入，金额 0 不出单）。 */
    private Map<Long, List<InvigilatorAppointment>> billableByStaff(String period) {
        List<InvigilatorAppointment> all = appointmentMapper.selectList(
                new QueryWrapper<InvigilatorAppointment>()
                        .eq("pay_status", StaffConstants.PAY_CALCULATED)
                        .eq("status", StaffConstants.APPOINT_CHECKED_IN)
                        .isNotNull("invigilator_id")
                        .orderByAsc("batch_id", "id"));
        Map<Long, List<InvigilatorAppointment>> byStaff = new LinkedHashMap<>();
        for (InvigilatorAppointment appt : all) {
            if (!period.equals(periodOf(appt))) {
                continue;
            }
            byStaff.computeIfAbsent(appt.getInvigilatorId(), k -> new ArrayList<>()).add(appt);
        }
        return byStaff;
    }

    private Map<Long, InvigilatorSettlement> existingByStaff(String period) {
        Map<Long, InvigilatorSettlement> map = new HashMap<>();
        for (InvigilatorSettlement s : settlementMapper.selectList(
                new QueryWrapper<InvigilatorSettlement>().eq("period", period))) {
            map.put(s.getInvigilatorId(), s);
        }
        return map;
    }

    /** 归属月份：优先批次考试日期；缺失时用签到钟点。 */
    private String periodOf(InvigilatorAppointment appt) {
        ExamBatch batch = batchService.getBatch(appt.getBatchId());
        LocalDate date = batch.getExamDate();
        if (date == null && appt.getCheckedInAt() != null) {
            date = appt.getCheckedInAt().toLocalDate();
        }
        if (date == null) {
            date = LocalDate.now();
        }
        return date.format(PERIOD_FMT);
    }

    private void insertItems(InvigilatorSettlement statement, List<InvigilatorAppointment> sessions) {
        int seq = 1;
        for (InvigilatorAppointment appt : sessions) {
            ExamBatch batch = batchService.getBatch(appt.getBatchId());
            InvigilatorSettlementItem item = new InvigilatorSettlementItem();
            item.setStatementId(statement.getId());
            item.setPeriod(statement.getPeriod());
            item.setInvigilatorId(statement.getInvigilatorId());
            item.setAppointmentId(appt.getId());
            item.setAppointmentNo(appt.getAppointmentNo());
            item.setAppointmentVersionNo(appt.getCurrentVersionNo());
            item.setBatchId(batch.getId());
            item.setBatchCode(batch.getCode());
            item.setBatchName(batch.getName());
            item.setExamDate(batch.getExamDate());
            item.setRoomId(appt.getRoomId());
            if (appt.getRoomId() != null) {
                ExamRoom room = roomMapper.selectById(appt.getRoomId());
                if (room != null) {
                    item.setRoomNo(room.getRoomNo());
                }
            }
            item.setRoleCode(appt.getRoleCode());
            item.setStipendVersionNo(appt.getStipendVersionNo());
            item.setRateSnapshot(appt.getRateSnapshot());
            item.setSessionIndexInBatch(appt.getSessionIndexInBatch());
            item.setItemType(StaffConstants.ITEM_SESSION);
            item.setAmount(appt.getAmount());
            item.setRemark("第 " + seq + " 场 · " + StipendService.roleName(appt.getRoleCode()));
            itemMapper.insert(item);
            seq++;
        }
    }

    private void bindAppointments(List<InvigilatorAppointment> sessions, Long statementId) {
        for (InvigilatorAppointment appt : sessions) {
            appt.setSettledInStatementId(statementId);
            appointmentMapper.updateById(appt);
        }
    }

    /** 草稿重建：删旧明细并解除聘任挂单（锁定单不会走到这里）。 */
    private void clearItemsAndUnbind(Long statementId) {
        List<InvigilatorAppointment> bound = appointmentMapper.selectList(
                new QueryWrapper<InvigilatorAppointment>()
                        .eq("settled_in_statement_id", statementId));
        for (InvigilatorAppointment appt : bound) {
            appt.setSettledInStatementId(null);
            appointmentMapper.updateById(appt);
        }
        itemMapper.delete(new QueryWrapper<InvigilatorSettlementItem>()
                .eq("statement_id", statementId));
    }

    private BigDecimal recalcAdjustAmount(Long statementId) {
        BigDecimal total = BigDecimal.ZERO;
        for (InvigilatorSettlementAdjust a : adjustMapper.selectList(
                new QueryWrapper<InvigilatorSettlementAdjust>().eq("statement_id", statementId))) {
            if (StaffConstants.ADJUST_SUPPLEMENT.equals(a.getAdjustType())) {
                total = total.add(a.getAmount());
            } else {
                total = total.subtract(a.getAmount());
            }
        }
        return total;
    }

    private int countAdjusts(Long statementId) {
        Long count = adjustMapper.selectCount(new QueryWrapper<InvigilatorSettlementAdjust>()
                .eq("statement_id", statementId));
        return count == null ? 0 : count.intValue();
    }

    private BigDecimal sumAmount(List<InvigilatorAppointment> sessions) {
        BigDecimal total = BigDecimal.ZERO;
        for (InvigilatorAppointment a : sessions) {
            total = total.add(a.getAmount() == null ? BigDecimal.ZERO : a.getAmount());
        }
        return total.setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    private InvigilatorSettlement requireStatement(Long id) {
        if (id == null) {
            throw new BusinessException("SETTLEMENT_ID_REQUIRED", "结算单 id 不能为空");
        }
        InvigilatorSettlement statement = settlementMapper.selectById(id);
        if (statement == null) {
            throw new BusinessException("SETTLEMENT_NOT_FOUND", "结算单不存在：id=" + id);
        }
        return statement;
    }

    /** 结算单号：JS + 月份 + - + 4 位全局流水。 */
    private String nextStatementNo(String period) {
        Long count = settlementMapper.selectCount(null);
        return "JS" + period + "-" + String.format("%04d", (count == null ? 0 : count) + 1);
    }

    /** 调整单号：ADJ + 结算单尾号 + - + 调整序号。 */
    private String nextAdjustNo(InvigilatorSettlement statement) {
        Long count = adjustMapper.selectCount(new QueryWrapper<InvigilatorSettlementAdjust>()
                .eq("statement_id", statement.getId()));
        int seq = (count == null ? 0 : count.intValue()) + 1;
        return "ADJ" + statement.getStatementNo() + "-" + String.format("%02d", seq);
    }

    private String normalizePeriod(String period) {
        if (period == null || !period.trim().matches("\\d{6}")) {
            throw new BusinessException("PERIOD_INVALID", "结算归属月份格式不正确，应为 yyyyMM，如 202609");
        }
        String p = period.trim();
        try {
            YearMonth.parse(p, PERIOD_FMT);
        } catch (DateTimeParseException e) {
            throw new BusinessException("PERIOD_INVALID", "结算归属月份不是合法月份：" + p);
        }
        return p;
    }
}
