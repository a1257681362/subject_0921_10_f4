package com.evops.staff.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.exam.entity.AdmissionTicket;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.entity.ExamRoom;
import com.evops.exam.mapper.AdmissionTicketMapper;
import com.evops.exam.mapper.ExamRoomMapper;
import com.evops.exam.service.ExamBatchService;
import com.evops.exam.vo.AffectedItem;
import com.evops.staff.common.StaffConstants;
import com.evops.staff.dto.AppointmentManualRequest;
import com.evops.staff.dto.CheckinRequest;
import com.evops.staff.entity.Invigilator;
import com.evops.staff.entity.InvigilatorAppointment;
import com.evops.staff.entity.InvigilatorAppointmentVersion;
import com.evops.staff.entity.InvigilatorCheckin;
import com.evops.staff.entity.InvigilatorStipendVersion;
import com.evops.staff.mapper.InvigilatorAppointmentMapper;
import com.evops.staff.mapper.InvigilatorAppointmentVersionMapper;
import com.evops.staff.mapper.InvigilatorCheckinMapper;
import com.evops.staff.vo.StaffActionPreview;
import com.evops.staff.vo.AppointmentGenPreview;
import com.evops.staff.vo.AppointmentView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 聘任记录模块（聘任记录 + 版本 + 状态）：
 * - 监考随排班生成聘任：编排 / 重排落库后，按实际使用考场同步 INVIGILATOR 槽位（重排冲销多余槽位）；
 * - 人事可补派主考 / 巡考等考点级槽位，可手工补聘；
 * - 换人 / 取消只追加版本（APPOINT→REPLACE/CANCEL），主表反映当前态，row_version 递增；
 * - 场次结束签到后计津贴：PRESENT/LATE 按角色标准快照 + 超场补贴计酬，ABSENT 缺勤金额 0 不计费；
 * - 已进入结算单的聘任冻结，换人 / 撤销签到一律拒绝，要改走结算调整单。
 */
@Service
public class AppointmentService {

    private final InvigilatorAppointmentMapper appointmentMapper;
    private final InvigilatorAppointmentVersionMapper versionMapper;
    private final InvigilatorCheckinMapper checkinMapper;
    private final InvigilatorService invigilatorService;
    private final StipendService stipendService;
    private final ExamBatchService batchService;
    private final ExamRoomMapper roomMapper;
    private final AdmissionTicketMapper ticketMapper;

    public AppointmentService(InvigilatorAppointmentMapper appointmentMapper,
                              InvigilatorAppointmentVersionMapper versionMapper,
                              InvigilatorCheckinMapper checkinMapper,
                              InvigilatorService invigilatorService,
                              StipendService stipendService,
                              ExamBatchService batchService,
                              ExamRoomMapper roomMapper,
                              AdmissionTicketMapper ticketMapper) {
        this.appointmentMapper = appointmentMapper;
        this.versionMapper = versionMapper;
        this.checkinMapper = checkinMapper;
        this.invigilatorService = invigilatorService;
        this.stipendService = stipendService;
        this.batchService = batchService;
        this.roomMapper = roomMapper;
        this.ticketMapper = ticketMapper;
    }

    // ====================================================================
    // 排班生成聘任（编排 / 重排事务内调用）
    // ====================================================================

    /**
     * 按批次实际使用考场同步监考员聘任槽位：
     * - 新考场：建 PENDING 槽位（未指派人，待人事派班 / 换人）；
     * - 仍使用的考场：槽位保留不动（已签到 / 已换人记录不受影响）；
     * - 重排后不再使用的考场：PENDING 槽位取消并留 CANCEL 版本；
     *   已签到 / 缺勤槽位保留（场次事实不抹）。
     * 津贴标准未配置时槽位照常生成、标准快照留空，签到计酬前配置即可（签到时补快照）。
     */
    @Transactional
    public void syncForArrangement(Long batchId, List<Long> usedRoomIds, String operator,
                                   Map<Long, String> roomStaffAssignments) {
        ExamBatch batch = batchService.getBatch(batchId);
        Set<Long> used = new HashSet<>(usedRoomIds == null ? new ArrayList<>() : usedRoomIds);

        List<InvigilatorAppointment> existing = appointmentMapper.selectList(
                new QueryWrapper<InvigilatorAppointment>()
                        .eq("batch_id", batchId)
                        .eq("role_code", StaffConstants.ROLE_INVIGILATOR));

        // 不再使用考场上的待执行槽位：取消
        for (InvigilatorAppointment appt : existing) {
            Long roomId = appt.getRoomId();
            if (roomId != null && !used.contains(roomId)
                    && StaffConstants.APPOINT_PENDING.equals(appt.getStatus())) {
                cancelInternal(appt, "整批重排后该考场不再使用，聘任自动取消", operator);
            }
        }

        // 新使用考场：建槽位并按需派班
        Set<Long> existingRooms = new HashSet<>();
        for (InvigilatorAppointment appt : existing) {
            if (appt.getRoomId() != null
                    && !StaffConstants.APPOINT_CANCELLED.equals(appt.getStatus())) {
                existingRooms.add(appt.getRoomId());
            }
        }
        Map<Long, String> assignments = roomStaffAssignments == null
                ? new HashMap<>() : roomStaffAssignments;
        for (Long roomId : used) {
            if (existingRooms.contains(roomId)) {
                continue;
            }
            String staffNo = assignments.get(roomId);
            createSlot(batch, roomId, StaffConstants.SLOT_ROOM, StaffConstants.ROLE_INVIGILATOR,
                    StaffConstants.SOURCE_AUTO,
                    staffNo == null || staffNo.trim().isEmpty() ? null : staffNo.trim(),
                    "随排班生成聘任", operator);
        }
    }

    /** 无派班信息的便捷重载（编排钩子使用）。 */
    @Transactional
    public void syncForArrangement(Long batchId, List<Long> usedRoomIds, String operator) {
        syncForArrangement(batchId, usedRoomIds, operator, null);
    }

    // ---------- 生成预检 / 执行（人事按批次手动触发，可带派班表） ----------

    @Transactional(readOnly = true)
    public AppointmentGenPreview previewGenerate(Long batchId, Map<Long, String> assignments) {
        ExamBatch batch = batchService.getBatch(batchId);
        List<ExamRoom> rooms = usedRooms(batchId, batch);
        List<InvigilatorAppointment> existing = appointmentMapper.selectList(
                new QueryWrapper<InvigilatorAppointment>()
                        .eq("batch_id", batchId)
                        .eq("role_code", StaffConstants.ROLE_INVIGILATOR));
        Map<Long, InvigilatorAppointment> activeByRoom = new HashMap<>();
        int cancelCount = 0;
        for (InvigilatorAppointment a : existing) {
            if (a.getRoomId() == null) {
                continue;
            }
            if (StaffConstants.APPOINT_CANCELLED.equals(a.getStatus())) {
                cancelCount++;
                continue;
            }
            activeByRoom.put(a.getRoomId(), a);
        }

        AppointmentGenPreview preview = new AppointmentGenPreview();
        preview.setBatchId(batchId);
        preview.setBatchCode(batch.getCode());
        preview.setRoomCount(rooms.size());

        Set<Long> usedRoomIds = new HashSet<>();
        int createCount = 0;
        int keepCount = 0;
        int unassigned = 0;
        for (ExamRoom room : rooms) {
            usedRoomIds.add(room.getId());
            AppointmentGenPreview.SlotPlan plan = new AppointmentGenPreview.SlotPlan();
            plan.setRoomId(room.getId());
            plan.setRoomNo(room.getRoomNo());
            plan.setRoleCode(StaffConstants.ROLE_INVIGILATOR);
            InvigilatorAppointment appt = activeByRoom.get(room.getId());
            String staffNo = assignments == null ? null : assignments.get(room.getId());
            if (appt == null) {
                plan.setAction("CREATE");
                createCount++;
                if (staffNo == null || staffNo.trim().isEmpty()) {
                    unassigned++;
                    plan.setActionable(true);
                    plan.setNote("将生成监考员聘任槽位，暂未派人，可后续派班");
                } else {
                    fillStaffPlan(plan, staffNo);
                }
            } else {
                plan.setAction("KEEP");
                keepCount++;
                plan.setStaffNo(staffNameOf(appt.getInvigilatorId(), true)[0]);
                plan.setStaffName(staffNameOf(appt.getInvigilatorId(), false)[1]);
                plan.setActionable(false);
                plan.setNote("槽位已存在（当前版本 " + appt.getCurrentVersionNo()
                        + "，状态 " + appt.getStatus() + "），保留不动");
            }
            preview.getItems().add(plan);
        }

        // 重排后已不再使用、且仍待执行的槽位将被取消
        int willCancel = 0;
        for (InvigilatorAppointment a : activeByRoom.values()) {
            if (!usedRoomIds.contains(a.getRoomId())
                    && StaffConstants.APPOINT_PENDING.equals(a.getStatus())) {
                willCancel++;
                AppointmentGenPreview.SlotPlan plan = new AppointmentGenPreview.SlotPlan();
                plan.setAction("CANCEL");
                plan.setRoomId(a.getRoomId());
                plan.setRoleCode(a.getRoleCode());
                plan.setActionable(true);
                plan.setNote("考场不再使用，待执行聘任将取消（已签到 / 缺勤的保留）");
                preview.getItems().add(plan);
            }
        }

        preview.setCreateCount(createCount);
        preview.setKeepCount(keepCount);
        preview.setCancelCount(willCancel);
        preview.setUnassignedCount(unassigned);
        preview.setSummary(String.format(
                "批次 %s：使用考场 %d 间；将新建聘任槽位 %d 个（其中 %d 个待派班），保留已有槽位 %d 个，取消多余待执行槽位 %d 个",
                batch.getCode(), rooms.size(), createCount, unassigned, keepCount, willCancel));
        return preview;
    }

    @Transactional
    public AppointmentGenPreview generate(Long batchId, Map<Long, String> assignments) {
        // 预检与执行共用同一套使用考场口径，保证两步一致
        AppointmentGenPreview preview = previewGenerate(batchId, assignments);
        List<Long> roomIds = new ArrayList<>();
        for (AppointmentGenPreview.SlotPlan plan : preview.getItems()) {
            if ("CANCEL".equals(plan.getAction())) {
                continue;
            }
            roomIds.add(plan.getRoomId());
        }
        syncForArrangement(batchId, roomIds, OperatorHolder.current(), assignments);
        return previewGenerate(batchId, assignments);
    }

    // ====================================================================
    // 人事手工补聘（主考 / 巡考考点级槽位，或指定考场监考员）
    // ====================================================================

    @Transactional
    public InvigilatorAppointment manualAppoint(AppointmentManualRequest request) {
        ExamBatch batch = batchService.getBatch(request.getBatchId());
        String role = request.getRoleCode().trim().toUpperCase();
        String slotType;
        if (request.getRoomId() == null) {
            slotType = StaffConstants.SLOT_SITE;
            if (!StaffConstants.ROLE_CHIEF.equals(role) && !StaffConstants.ROLE_PATROL.equals(role)
                    && !StaffConstants.ROLE_BACKUP.equals(role)) {
                throw new BusinessException("ROOM_REQUIRED_FOR_ROLE",
                        "角色 " + StipendService.roleName(role) + " 必须指定考场（考场级槽位）");
            }
        } else {
            slotType = StaffConstants.SLOT_ROOM;
            ExamRoom room = roomMapper.selectById(request.getRoomId());
            if (room == null || !room.getSiteId().equals(batch.getSiteId())) {
                throw new BusinessException("ROOM_NOT_IN_BATCH_SITE",
                        "考场不属于本批次考点：roomId=" + request.getRoomId());
            }
        }

        InvigilatorAppointment appt = createSlot(batch, request.getRoomId(), slotType, role,
                StaffConstants.SOURCE_MANUAL,
                request.getStaffNo() == null || request.getStaffNo().trim().isEmpty()
                        ? null : request.getStaffNo().trim(),
                "人事手工补聘", OperatorHolder.current());
        return appointmentMapper.selectById(appt.getId());
    }

    // ====================================================================
    // 换人（留替换记录）/ 取消
    // ====================================================================

    @Transactional
    public InvigilatorAppointment replace(Long appointmentId, String staffNo, String reason) {
        InvigilatorAppointment appt = requireAppointment(appointmentId);
        assertNotSettled(appt);
        if (StaffConstants.APPOINT_CANCELLED.equals(appt.getStatus())) {
            throw new BusinessException("APPOINTMENT_CANCELLED",
                    "聘任已取消，不能换人；如需用人请重新补聘：" + appt.getAppointmentNo());
        }
        if (StaffConstants.APPOINT_CHECKED_IN.equals(appt.getStatus())) {
            throw new BusinessException("ALREADY_CHECKED_IN",
                    "该聘任已签到计酬，不能直接换人；如确属误签，请先撤销签到（未进结算单）："
                            + appt.getAppointmentNo());
        }
        return assignInternal(appt, staffNo, reason, OperatorHolder.current());
    }

    @Transactional
    public InvigilatorAppointment cancel(Long appointmentId, String reason) {
        InvigilatorAppointment appt = requireAppointment(appointmentId);
        assertNotSettled(appt);
        if (StaffConstants.APPOINT_CANCELLED.equals(appt.getStatus())) {
            throw new BusinessException("APPOINTMENT_ALREADY_CANCELLED",
                    "聘任已是取消状态：" + appt.getAppointmentNo());
        }
        if (StaffConstants.APPOINT_CHECKED_IN.equals(appt.getStatus())) {
            throw new BusinessException("ALREADY_CHECKED_IN",
                    "已签到计酬的聘任不能取消；计酬差错请走结算调整单：" + appt.getAppointmentNo());
        }
        return cancelInternal(appt, reason, OperatorHolder.current());
    }

    // ====================================================================
    // 场次签到（两步：预检 → 执行）；缺勤不计费
    // ====================================================================

    @Transactional(readOnly = true)
    public StaffActionPreview previewCheckin(CheckinRequest request) {
        List<CheckinRow> rows = resolveCheckinRows(request);
        // 模拟本次请求内"即将计酬"的场次顺序（同人同日多场连签时，预检序号与执行后一致）
        Map<Long, List<Long>> virtualByStaff = new HashMap<>();
        StaffActionPreview preview = new StaffActionPreview();
        preview.setAction("CHECKIN");
        preview.setTotalScanned(rows.size());
        int actionable = 0;
        int skip = 0;
        List<AffectedItem> items = new ArrayList<>();
        for (CheckinRow row : rows) {
            String result = row.result;
            boolean ok = row.error == null;
            String note;
            if (ok) {
                actionable++;
                List<Long> virtualEarlier = row.staff == null
                        ? new ArrayList<>()
                        : virtualByStaff.getOrDefault(row.staff.getId(), new ArrayList<>());
                if (StaffConstants.CHECKIN_ABSENT.equals(result)) {
                    note = String.format("缺勤标记：%s（%s）%s，本场不计费，金额 0 元",
                            row.staff.getStaffNo(), row.staff.getName(),
                            StipendService.roleName(row.appt.getRoleCode()));
                } else {
                    int index = nextSessionIndex(row.appt, virtualEarlier);
                    BigDecimal amount = computeAmount(row.appt, index);
                    row.sessionIndex = index;
                    row.amount = amount;
                    note = String.format("%s：%s（%s）%s 第 %d 场，标准 %s 元/场%s，计 %s 元",
                            StaffConstants.CHECKIN_LATE.equals(result) ? "迟到（视同到场）" : "到场",
                            row.staff.getStaffNo(), row.staff.getName(),
                            StipendService.roleName(row.appt.getRoleCode()), index,
                            row.appt.getRateSnapshot(),
                            index > 1 && row.appt.getExtraSnapshot() != null
                                    ? "，超场补贴 " + row.appt.getExtraSnapshot() + " 元/场" : "",
                            amount);
                    virtualByStaff.computeIfAbsent(row.staff.getId(), k -> new ArrayList<>())
                            .add(row.appt.getId());
                }
            } else {
                skip++;
                note = row.error;
            }
            items.add(new AffectedItem(row.appt.getAppointmentNo(), row.staff == null ? "-" : row.staff.getName(),
                    row.appt.getStatus(), ok, note));
        }
        preview.setActionableCount(actionable);
        preview.setSkipCount(skip);
        preview.setSummary(String.format("签到扫描 %d 条：可处理 %d 条，跳过 %d 条（缺勤不计费）",
                rows.size(), actionable, skip));
        preview.setItems(items);
        return preview;
    }

    @Transactional
    public StaffActionPreview executeCheckin(CheckinRequest request) {
        List<CheckinRow> rows = resolveCheckinRows(request);
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();
        int processed = 0;
        int skipped = 0;
        List<AffectedItem> itemResults = new ArrayList<>();

        for (CheckinRow row : rows) {
            if (row.error != null) {
                skipped++;
                itemResults.add(new AffectedItem(row.appt == null ? "-" : row.appt.getAppointmentNo(),
                        row.staff == null ? "-" : row.staff.getName(),
                        row.appt == null ? "-" : row.appt.getStatus(), false, row.error));
                continue;
            }
            assertNotSettled(row.appt);
            InvigilatorAppointment appt = row.appt;
            String result = row.result;

            // 缺标准快照的槽位（排班时标准尚未发布）：到场签到时补快照；缺勤金额为 0，不依赖标准
            if (!StaffConstants.CHECKIN_ABSENT.equals(result)) {
                ensureRateSnapshot(appt);
            }

            InvigilatorCheckin checkin = new InvigilatorCheckin();
            checkin.setAppointmentId(appt.getId());
            checkin.setBatchId(appt.getBatchId());
            checkin.setRoomId(appt.getRoomId());
            checkin.setInvigilatorId(row.staff.getId());
            checkin.setRoleCode(appt.getRoleCode());
            checkin.setResult(result);
            checkin.setCheckinAt(now);
            checkin.setOperator(operator);
            checkin.setNote(row.note);
            try {
                checkinMapper.insert(checkin);
            } catch (DuplicateKeyException e) {
                skipped++;
                itemResults.add(new AffectedItem(appt.getAppointmentNo(), row.staff.getName(),
                        appt.getStatus(), false, "已存在签到结果，不能重复签到（并发插入冲突）"));
                continue;
            }

            String note;
            if (StaffConstants.CHECKIN_ABSENT.equals(result)) {
                appt.setStatus(StaffConstants.APPOINT_ABSENT);
                appt.setPayStatus(StaffConstants.PAY_NONE);
                appt.setAmount(BigDecimal.ZERO);
                appt.setSessionIndexInBatch(null);
                appt.setAbsentMarkedBy(operator);
                appt.setAbsentMarkedAt(now);
                note = String.format("缺勤已标记：%s（%s）%s，本场不计费，金额 0 元",
                        row.staff.getStaffNo(), row.staff.getName(),
                        StipendService.roleName(appt.getRoleCode()));
            } else {
                int index = nextSessionIndex(appt);
                BigDecimal amount = computeAmount(appt, index);
                appt.setStatus(StaffConstants.APPOINT_CHECKED_IN);
                appt.setPayStatus(StaffConstants.PAY_CALCULATED);
                appt.setAmount(amount);
                appt.setSessionIndexInBatch(index);
                appt.setCheckedInBy(operator);
                appt.setCheckedInAt(now);
                appt.setAbsentMarkedBy(null);
                appt.setAbsentMarkedAt(null);
                note = String.format("%s已计酬：%s（%s）%s 第 %d 场，标准 %s 元/场%s，计 %s 元",
                        StaffConstants.CHECKIN_LATE.equals(result) ? "迟到（视同到场）" : "到场",
                        row.staff.getStaffNo(), row.staff.getName(),
                        StipendService.roleName(appt.getRoleCode()), index,
                        appt.getRateSnapshot(),
                        index > 1 && appt.getExtraSnapshot() != null
                                ? "，超场补贴 " + appt.getExtraSnapshot() + " 元/场" : "",
                        amount);
            }
            appt.setRowVersion(appt.getRowVersion() == null ? 1 : appt.getRowVersion() + 1);
            appointmentMapper.updateById(appt);
            processed++;
            itemResults.add(new AffectedItem(appt.getAppointmentNo(), row.staff.getName(),
                    appt.getStatus(), true, note));
        }

        StaffActionPreview result = new StaffActionPreview();
        result.setAction("CHECKIN");
        result.setTotalScanned(rows.size());
        result.setActionableCount(processed);
        result.setSkipCount(skipped);
        result.setItems(itemResults);
        result.setSummary(String.format("签到处理完成：实际入账 %d 条（缺勤不计费），跳过 %d 条",
                processed, skipped));
        return result;
    }

    /** 撤销误签：仅未进结算单的聘任可撤销，回到待执行、金额清零。 */
    @Transactional
    public InvigilatorAppointment revokeCheckin(Long appointmentId, String reason) {
        InvigilatorAppointment appt = requireAppointment(appointmentId);
        assertNotSettled(appt);
        InvigilatorCheckin checkin = checkinMapper.selectOne(new QueryWrapper<InvigilatorCheckin>()
                .eq("appointment_id", appointmentId));
        if (checkin == null) {
            throw new BusinessException("CHECKIN_NOT_FOUND", "该聘任没有签到记录，无需撤销：" + appt.getAppointmentNo());
        }
        checkinMapper.deleteById(checkin.getId());
        appt.setStatus(StaffConstants.APPOINT_PENDING);
        appt.setPayStatus(StaffConstants.PAY_NONE);
        appt.setAmount(BigDecimal.ZERO);
        appt.setSessionIndexInBatch(null);
        appt.setCheckedInAt(null);
        appt.setCheckedInBy(null);
        appt.setAbsentMarkedAt(null);
        appt.setAbsentMarkedBy(null);
        appt.setRowVersion(appt.getRowVersion() == null ? 1 : appt.getRowVersion() + 1);
        appointmentMapper.updateById(appt);

        InvigilatorAppointmentVersion v = buildVersion(appt, StaffConstants.CHANGE_REVOKE,
                appt.getInvigilatorId(), appt.getInvigilatorId(), appt.getInvigilatorId(),
                "撤销签到回退待执行" + (reason == null || reason.trim().isEmpty() ? "" : "：" + reason));
        versionMapper.insert(v);
        appt.setCurrentVersionNo(v.getVersionNo());
        appointmentMapper.updateById(appt);
        return appt;
    }

    // ====================================================================
    // 查询（人事全量；本人视角由 Controller 传 staffNo 过滤）
    // ====================================================================

    @Transactional(readOnly = true)
    public List<AppointmentView> listViews(Long batchId, String staffNo, String status, String payStatus) {
        QueryWrapper<InvigilatorAppointment> wrapper = new QueryWrapper<InvigilatorAppointment>()
                .orderByDesc("batch_id").orderByAsc("room_id").orderByAsc("role_code");
        if (batchId != null) {
            wrapper.eq("batch_id", batchId);
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq("status", status.trim());
        }
        if (payStatus != null && !payStatus.trim().isEmpty()) {
            wrapper.eq("pay_status", payStatus.trim());
        }
        List<InvigilatorAppointment> list = appointmentMapper.selectList(wrapper);

        List<Invigilator> staffList = invigilatorService.list(null, null);
        Map<Long, Invigilator> staffMap = new HashMap<>();
        for (Invigilator s : staffList) {
            staffMap.put(s.getId(), s);
        }

        List<AppointmentView> views = new ArrayList<>();
        for (InvigilatorAppointment appt : list) {
            Invigilator staff = appt.getInvigilatorId() == null ? null : staffMap.get(appt.getInvigilatorId());
            if (staffNo != null && !staffNo.trim().isEmpty()) {
                if (staff == null || !staff.getStaffNo().equals(staffNo.trim())) {
                    continue;
                }
            }
            views.add(toView(appt, staff));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public AppointmentView getView(Long appointmentId) {
        InvigilatorAppointment appt = requireAppointment(appointmentId);
        Invigilator staff = appt.getInvigilatorId() == null
                ? null : invigilatorService.get(appt.getInvigilatorId());
        return toView(appt, staff);
    }

    @Transactional(readOnly = true)
    public List<InvigilatorAppointmentVersion> listVersions(Long appointmentId) {
        requireAppointment(appointmentId);
        return versionMapper.selectList(new QueryWrapper<InvigilatorAppointmentVersion>()
                .eq("appointment_id", appointmentId)
                .orderByAsc("version_seq"));
    }

    // ====================================================================
    // 内部
    // ====================================================================

    private InvigilatorAppointment createSlot(ExamBatch batch, Long roomId, String slotType,
                                              String roleCode, String source, String staffNo,
                                              String reason, String operator) {
        // 同批次同槽位只允许一条"未取消"聘任（已取消的历史行保留，不拦重建）
        QueryWrapper<InvigilatorAppointment> dupWrapper = new QueryWrapper<InvigilatorAppointment>()
                .eq("batch_id", batch.getId())
                .eq("role_code", roleCode)
                .ne("status", StaffConstants.APPOINT_CANCELLED);
        if (roomId == null) {
            dupWrapper.isNull("room_id");
        } else {
            dupWrapper.eq("room_id", roomId);
        }
        InvigilatorAppointment dup = appointmentMapper.selectOne(dupWrapper.last("LIMIT 1"));
        if (dup != null) {
            throw new BusinessException("APPOINTMENT_SLOT_EXISTS",
                    "批次 " + batch.getCode()
                            + (roomId == null ? " 的考点级角色 " : " 该考场的角色 ")
                            + StipendService.roleName(roleCode) + " 聘任已存在：" + dup.getAppointmentNo());
        }

        // 初聘带人的场景：先校验人选（停用 / 角色不符 / 本批次已有聘任均拒收）
        Invigilator staff = null;
        if (staffNo != null && !staffNo.trim().isEmpty()) {
            staff = invigilatorService.requireEnabledForRole(staffNo.trim(), roleCode);
            Long collision = appointmentMapper.selectCount(new QueryWrapper<InvigilatorAppointment>()
                    .eq("batch_id", batch.getId())
                    .eq("invigilator_id", staff.getId())
                    .in("status", StaffConstants.APPOINT_PENDING, StaffConstants.APPOINT_CHECKED_IN));
            if (collision != null && collision > 0) {
                throw new BusinessException("INVIGILATOR_BUSY",
                        "监考员 " + staff.getStaffNo() + "（" + staff.getName()
                                + "）在本批次已有未结束的监考聘任，不能重复派班");
            }
        }

        InvigilatorAppointment appt = new InvigilatorAppointment();
        appt.setAppointmentNo(nextAppointmentNo(batch));
        appt.setBatchId(batch.getId());
        appt.setRoomId(roomId);
        appt.setSlotType(slotType);
        appt.setRoleCode(roleCode);
        appt.setSource(source);
        appt.setStatus(StaffConstants.APPOINT_PENDING);
        appt.setPayStatus(StaffConstants.PAY_NONE);
        appt.setAmount(BigDecimal.ZERO);
        appt.setRowVersion(1);
        appt.setOperator(operator);
        if (staff != null) {
            appt.setInvigilatorId(staff.getId());
        }
        snapshotRateIfConfigured(appt, batch.getProjectCode(), roleCode);
        try {
            appointmentMapper.insert(appt);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("APPOINTMENT_SLOT_EXISTS",
                    "该批次考场的角色聘任槽位已存在（唯一约束冲突）");
        }

        InvigilatorAppointmentVersion v = buildVersion(appt, StaffConstants.CHANGE_APPOINT,
                staff == null ? null : staff.getId(), null,
                staff == null ? null : staff.getId(),
                reason == null ? "初聘生成（" + source + "）" : reason);
        versionMapper.insert(v);
        appt.setCurrentVersionNo(v.getVersionNo());
        appointmentMapper.updateById(appt);
        return appt;
    }

    /**
     * 派班 / 换人共用：校验新人选，追加 REPLACE 版本，主表切到新人并回退待执行。
     */
    private InvigilatorAppointment assignInternal(InvigilatorAppointment appt, String staffNo,
                                                  String reason, String operator) {
        Invigilator staff = invigilatorService.requireEnabledForRole(staffNo, appt.getRoleCode());
        if (appt.getInvigilatorId() != null && staff.getId().equals(appt.getInvigilatorId())) {
            throw new BusinessException("SAME_INVIGILATOR",
                    "新任监考员与当前被聘人相同，无需换人：" + staff.getStaffNo());
        }
        // 同一批次同一时间只能承担一个聘任槽位（避免一人监两个考场）
        Long collision = appointmentMapper.selectCount(new QueryWrapper<InvigilatorAppointment>()
                .eq("batch_id", appt.getBatchId())
                .eq("invigilator_id", staff.getId())
                .in("status", StaffConstants.APPOINT_PENDING, StaffConstants.APPOINT_CHECKED_IN));
        if (collision != null && collision > 0) {
            throw new BusinessException("INVIGILATOR_BUSY",
                    "监考员 " + staff.getStaffNo() + "（" + staff.getName()
                            + "）在本批次已有未结束的监考聘任，不能重复派班");
        }

        Long fromId = appt.getInvigilatorId();

        // 原人若已被记缺勤，其缺勤签到随换人移除（新人要重新签到）
        InvigilatorCheckin oldCheckin = checkinMapper.selectOne(new QueryWrapper<InvigilatorCheckin>()
                .eq("appointment_id", appt.getId()));
        if (oldCheckin != null
                && (StaffConstants.APPOINT_ABSENT.equals(appt.getStatus())
                || StaffConstants.APPOINT_PENDING.equals(appt.getStatus()))) {
            checkinMapper.deleteById(oldCheckin.getId());
        }

        appt.setInvigilatorId(staff.getId());
        appt.setStatus(StaffConstants.APPOINT_PENDING);
        appt.setPayStatus(StaffConstants.PAY_NONE);
        appt.setAmount(BigDecimal.ZERO);
        appt.setSessionIndexInBatch(null);
        appt.setCheckedInAt(null);
        appt.setCheckedInBy(null);
        appt.setAbsentMarkedAt(null);
        appt.setAbsentMarkedBy(null);
        appt.setRowVersion(appt.getRowVersion() == null ? 1 : appt.getRowVersion() + 1);
        // 换人时按最新生效标准刷新快照（口径以新人本场签到时为准）
        ExamBatch batch = batchService.getBatch(appt.getBatchId());
        snapshotRateIfConfigured(appt, batch.getProjectCode(), appt.getRoleCode());
        appointmentMapper.updateById(appt);

        InvigilatorAppointmentVersion v = buildVersion(appt, StaffConstants.CHANGE_REPLACE,
                staff.getId(), fromId, staff.getId(), reason);
        versionMapper.insert(v);
        appt.setCurrentVersionNo(v.getVersionNo());
        appointmentMapper.updateById(appt);
        return appt;
    }

    private InvigilatorAppointment cancelInternal(InvigilatorAppointment appt, String reason, String operator) {
        appt.setStatus(StaffConstants.APPOINT_CANCELLED);
        appt.setPayStatus(StaffConstants.PAY_NONE);
        appt.setAmount(BigDecimal.ZERO);
        appt.setSessionIndexInBatch(null);
        appt.setRowVersion(appt.getRowVersion() == null ? 1 : appt.getRowVersion() + 1);
        appointmentMapper.updateById(appt);

        InvigilatorAppointmentVersion v = buildVersion(appt, StaffConstants.CHANGE_CANCEL,
                appt.getInvigilatorId(), appt.getInvigilatorId(), null, reason);
        versionMapper.insert(v);
        appt.setCurrentVersionNo(v.getVersionNo());
        appointmentMapper.updateById(appt);
        return appt;
    }

    private InvigilatorAppointmentVersion buildVersion(InvigilatorAppointment appt, String changeType,
                                                       Long invigilatorId,
                                                       Long fromId, Long toId, String reason) {
        int seq = nextVersionSeq(appt.getId());
        InvigilatorAppointmentVersion v = new InvigilatorAppointmentVersion();
        v.setAppointmentId(appt.getId());
        v.setAppointmentNo(appt.getAppointmentNo());
        v.setVersionSeq(seq);
        v.setVersionNo(appt.getAppointmentNo() + "-V" + seq);
        v.setChangeType(changeType);
        v.setStatus(appt.getStatus());
        v.setInvigilatorId(invigilatorId);
        v.setFromInvigilatorId(fromId);
        v.setToInvigilatorId(toId);
        v.setRoleCode(appt.getRoleCode());
        v.setStipendVersionId(appt.getStipendVersionId());
        v.setStipendVersionNo(appt.getStipendVersionNo());
        v.setRateSnapshot(appt.getRateSnapshot());
        v.setExtraSnapshot(appt.getExtraSnapshot());
        v.setPayStatus(appt.getPayStatus());
        v.setAmount(appt.getAmount());
        v.setReason(reason);
        v.setOperator(OperatorHolder.current());
        v.setChangedAt(LocalDateTime.now());
        return v;
    }

    private int nextVersionSeq(Long appointmentId) {
        List<InvigilatorAppointmentVersion> versions = versionMapper.selectList(
                new QueryWrapper<InvigilatorAppointmentVersion>()
                        .eq("appointment_id", appointmentId));
        int max = 0;
        for (InvigilatorAppointmentVersion v : versions) {
            max = Math.max(max, v.getVersionSeq() == null ? 0 : v.getVersionSeq());
        }
        return max + 1;
    }

    /** 聘任流水号：AP + 批次编码 + 4 位批次内流水。 */
    private String nextAppointmentNo(ExamBatch batch) {
        List<InvigilatorAppointment> all = appointmentMapper.selectList(
                new QueryWrapper<InvigilatorAppointment>().eq("batch_id", batch.getId()));
        return "AP" + batch.getCode() + "-" + String.format("%04d", all.size() + 1);
    }

    /** 排班建槽时尝试固化当前生效标准；未配置则留空（不阻塞编排），签到时再补。 */
    private void snapshotRateIfConfigured(InvigilatorAppointment appt, String projectCode, String roleCode) {
        try {
            InvigilatorStipendVersion v = stipendService.requireActiveVersion(projectCode, roleCode);
            appt.setStipendVersionId(v.getId());
            appt.setStipendVersionNo(v.getVersionNo());
            appt.setRateSnapshot(v.getRatePerSession());
            appt.setExtraSnapshot(v.getExtraPerSession());
        } catch (BusinessException e) {
            if ("STIPEND_NOT_CONFIGURED".equals(e.getCode())
                    || "STIPEND_VERSION_NOT_ACTIVE".equals(e.getCode())) {
                appt.setStipendVersionId(null);
                appt.setStipendVersionNo(null);
                appt.setRateSnapshot(null);
                appt.setExtraSnapshot(null);
            } else {
                throw e;
            }
        }
    }

    /** 签到计酬前必须有标准快照；标准缺失给出可读处理提示。 */
    private void ensureRateSnapshot(InvigilatorAppointment appt) {
        if (appt.getRateSnapshot() != null) {
            return;
        }
        ExamBatch batch = batchService.getBatch(appt.getBatchId());
        InvigilatorStipendVersion v = stipendService.requireActiveVersion(
                batch.getProjectCode(), appt.getRoleCode());
        appt.setStipendVersionId(v.getId());
        appt.setStipendVersionNo(v.getVersionNo());
        appt.setRateSnapshot(v.getRatePerSession());
        appt.setExtraSnapshot(v.getExtraPerSession());
    }

    /**
     * 同一考试日第几场（跨当天多个批次，按批次开考时间排序）。
     * 一个批次同一时段一人只能监一场（防冲突），"多监一场"发生在当天上午 / 下午等多个批次；
     * 口径只认已计酬（CALCULATED）场次并把当前场计入排序，预检与执行结果一致。
     */
    private int nextSessionIndex(InvigilatorAppointment current) {
        return nextSessionIndex(current, new ArrayList<>());
    }

    /**
     * @param virtualEarlierIds 预检时本次请求内排在前面、即将计酬的同监考员聘任 id
     */
    private int nextSessionIndex(InvigilatorAppointment current, List<Long> virtualEarlierIds) {
        if (current.getInvigilatorId() == null) {
            return 1;
        }
        ExamBatch currentBatch = batchService.getBatch(current.getBatchId());
        if (currentBatch.getExamDate() == null) {
            // 批次无考试日期无法排同日场次：退化为本人已计酬场次数 +1
            Long count = appointmentMapper.selectCount(new QueryWrapper<InvigilatorAppointment>()
                    .eq("invigilator_id", current.getInvigilatorId())
                    .eq("pay_status", StaffConstants.PAY_CALCULATED));
            return (count == null ? 0 : count.intValue())
                    + (virtualEarlierIds == null ? 0 : virtualEarlierIds.size()) + 1;
        }

        List<InvigilatorAppointment> candidates = appointmentMapper.selectList(
                new QueryWrapper<InvigilatorAppointment>()
                        .eq("invigilator_id", current.getInvigilatorId())
                        .eq("pay_status", StaffConstants.PAY_CALCULATED));
        // 预检把同请求前序到场（尚未提交）场次也计入排序
        if (virtualEarlierIds != null) {
            for (Long id : virtualEarlierIds) {
                if (id.equals(current.getId())) {
                    continue;
                }
                boolean exists = false;
                for (InvigilatorAppointment a : candidates) {
                    if (a.getId().equals(id)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    candidates.add(requireAppointment(id));
                }
            }
        }
        List<InvigilatorAppointment> sameDay = new ArrayList<>();
        for (InvigilatorAppointment a : candidates) {
            ExamBatch b = batchService.getBatch(a.getBatchId());
            if (currentBatch.getExamDate().equals(b.getExamDate())) {
                sameDay.add(a);
            }
        }
        sameDay.add(current);
        sameDay.sort((x, y) -> {
            ExamBatch bx = batchService.getBatch(x.getBatchId());
            ExamBatch by = batchService.getBatch(y.getBatchId());
            int c = bx.getStartTime() == null || by.getStartTime() == null
                    ? Long.compare(bx.getId(), by.getId())
                    : bx.getStartTime().compareTo(by.getStartTime());
            if (c != 0) {
                return c;
            }
            return Long.compare(x.getId(), y.getId());
        });
        for (int i = 0; i < sameDay.size(); i++) {
            if (sameDay.get(i).getId().equals(current.getId())) {
                return i + 1;
            }
        }
        return 1;
    }

    /** 第 N 场金额 = 单场标准 + (N-1) × 超场补贴。 */
    private BigDecimal computeAmount(InvigilatorAppointment appt, int sessionIndex) {
        BigDecimal rate = appt.getRateSnapshot();
        if (rate == null) {
            throw new BusinessException("STIPEND_RATE_MISSING",
                    "角色 " + StipendService.roleName(appt.getRoleCode())
                            + " 缺少生效津贴标准快照，无法计酬；请先发布标准后再签到");
        }
        BigDecimal amount = rate;
        if (sessionIndex > 1 && appt.getExtraSnapshot() != null) {
            amount = amount.add(appt.getExtraSnapshot().multiply(BigDecimal.valueOf(sessionIndex - 1L)));
        }
        return amount.setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    private List<CheckinRow> resolveCheckinRows(CheckinRequest request) {
        List<CheckinRow> rows = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (CheckinRequest.CheckinItem item : request.getItems()) {
            CheckinRow row = new CheckinRow();
            rows.add(row);
            if (item.getAppointmentId() == null || !seen.add(item.getAppointmentId())) {
                row.appt = item.getAppointmentId() == null ? null : requireAppointment(item.getAppointmentId());
                row.result = StaffConstants.CHECKIN_PRESENT;
                row.error = "聘任 id 为空或在本次请求中重复";
                continue;
            }
            InvigilatorAppointment appt = requireAppointment(item.getAppointmentId());
            row.appt = appt;
            row.note = item.getNote();

            String result = item.getResult() == null || item.getResult().trim().isEmpty()
                    ? StaffConstants.CHECKIN_PRESENT : item.getResult().trim().toUpperCase();
            if (!StaffConstants.CHECKIN_PRESENT.equals(result)
                    && !StaffConstants.CHECKIN_LATE.equals(result)
                    && !StaffConstants.CHECKIN_ABSENT.equals(result)) {
                row.result = result;
                row.error = "签到结果不支持：" + result + "（仅 PRESENT/LATE/ABSENT）";
                continue;
            }
            row.result = result;

            if (StaffConstants.APPOINT_CANCELLED.equals(appt.getStatus())) {
                row.error = "聘任已取消，不能签到：" + appt.getAppointmentNo();
                continue;
            }
            InvigilatorCheckin existed = checkinMapper.selectOne(new QueryWrapper<InvigilatorCheckin>()
                    .eq("appointment_id", appt.getId()));
            if (existed != null) {
                row.error = "已存在签到结果（" + existed.getResult() + "），不能重复签到；"
                        + (appt.getSettledInStatementId() == null ? "如需更正请先撤销签到" : "已进结算单，改走调整单");
                continue;
            }
            if (appt.getInvigilatorId() == null) {
                row.error = "聘任槽位尚未派人，不能签到：" + appt.getAppointmentNo() + "，请先派班 / 换人";
                continue;
            }
            // 缺生效津贴标准：预检 / 执行都按逐条跳过处理并给处理提示，而不是整单失败
            if (!StaffConstants.CHECKIN_ABSENT.equals(result) && appt.getRateSnapshot() == null) {
                try {
                    ExamBatch batch = batchService.getBatch(appt.getBatchId());
                    stipendService.requireActiveVersion(batch.getProjectCode(), appt.getRoleCode());
                } catch (BusinessException e) {
                    row.error = "角色 " + StipendService.roleName(appt.getRoleCode())
                            + " 缺少生效津贴标准，请先建档并发布后再签到（" + e.getMessage() + "）";
                    continue;
                }
            }
            row.staff = invigilatorService.get(appt.getInvigilatorId());
        }
        return rows;
    }

    /**
     * 排班使用考场：优先取当前编排实际分配了座位的考场（与座次表口径一致）；
     * 批次尚未编排时回退到考点启用考场（供人事先看槽位方案）。
     */
    private List<ExamRoom> usedRooms(Long batchId, ExamBatch batch) {
        List<AdmissionTicket> tickets = ticketMapper.selectList(new QueryWrapper<AdmissionTicket>()
                .eq("batch_id", batchId)
                .select("DISTINCT room_id"));
        Set<Long> roomIds = new HashSet<>();
        for (AdmissionTicket t : tickets) {
            if (t.getRoomId() != null) {
                roomIds.add(t.getRoomId());
            }
        }
        if (!roomIds.isEmpty()) {
            List<ExamRoom> rooms = roomMapper.selectBatchIds(roomIds);
            rooms.sort((a, b) -> a.getRoomNo().compareTo(b.getRoomNo()));
            return rooms;
        }
        return roomMapper.selectList(new QueryWrapper<ExamRoom>()
                .eq("site_id", batch.getSiteId())
                .eq("status", "ENABLED")
                .orderByAsc("room_no"));
    }

    private void fillStaffPlan(AppointmentGenPreview.SlotPlan plan, String staffNo) {
        try {
            Invigilator staff = invigilatorService.requireEnabledForRole(
                    staffNo, StaffConstants.ROLE_INVIGILATOR);
            plan.setStaffNo(staff.getStaffNo());
            plan.setStaffName(staff.getName());
            plan.setActionable(true);
            plan.setNote("将生成槽位并派班给 " + staff.getStaffNo() + "（" + staff.getName() + "）");
        } catch (BusinessException e) {
            plan.setStaffNo(staffNo);
            plan.setActionable(false);
            plan.setNote("派班校验不通过：" + e.getMessage());
        }
    }

    private String[] staffNameOf(Long invigilatorId, boolean staffNoOnly) {
        if (invigilatorId == null) {
            return new String[]{null, "未派人"};
        }
        Invigilator staff = invigilatorService.get(invigilatorId);
        return new String[]{staff.getStaffNo(), staff.getName()};
    }

    private InvigilatorAppointment requireAppointment(Long id) {
        if (id == null) {
            throw new BusinessException("APPOINTMENT_ID_REQUIRED", "聘任记录 id 不能为空");
        }
        InvigilatorAppointment appt = appointmentMapper.selectById(id);
        if (appt == null) {
            throw new BusinessException("APPOINTMENT_NOT_FOUND", "聘任记录不存在：id=" + id);
        }
        return appt;
    }

    private void assertNotSettled(InvigilatorAppointment appt) {
        if (appt.getSettledInStatementId() != null) {
            throw new BusinessException("APPOINTMENT_SETTLED_LOCKED",
                    "聘任已进入结算单（id=" + appt.getSettledInStatementId()
                            + "），记录锁定；如需更正请走结算调整单：" + appt.getAppointmentNo());
        }
    }

    private AppointmentView toView(InvigilatorAppointment appt, Invigilator staff) {
        AppointmentView v = new AppointmentView();
        v.setId(appt.getId());
        v.setAppointmentNo(appt.getAppointmentNo());
        v.setBatchId(appt.getBatchId());
        ExamBatch batch = batchService.getBatch(appt.getBatchId());
        v.setBatchCode(batch.getCode());
        v.setBatchName(batch.getName());
        v.setExamDate(batch.getExamDate());
        v.setRoomId(appt.getRoomId());
        if (appt.getRoomId() != null) {
            ExamRoom room = roomMapper.selectById(appt.getRoomId());
            if (room != null) {
                v.setRoomNo(room.getRoomNo());
            }
        }
        v.setSlotType(appt.getSlotType());
        v.setRoleCode(appt.getRoleCode());
        v.setRoleName(StipendService.roleName(appt.getRoleCode()));
        v.setCurrentVersionNo(appt.getCurrentVersionNo());
        v.setRowVersion(appt.getRowVersion());
        if (staff != null) {
            v.setInvigilatorId(staff.getId());
            v.setStaffNo(staff.getStaffNo());
            v.setInvigilatorName(staff.getName());
            v.setOrganization(staff.getOrganization());
        }
        v.setSource(appt.getSource());
        v.setStatus(appt.getStatus());
        v.setPayStatus(appt.getPayStatus());
        v.setStipendVersionId(appt.getStipendVersionId());
        v.setStipendVersionNo(appt.getStipendVersionNo());
        v.setRateSnapshot(appt.getRateSnapshot());
        v.setExtraSnapshot(appt.getExtraSnapshot());
        v.setAmount(appt.getAmount());
        v.setSessionIndexInBatch(appt.getSessionIndexInBatch());
        v.setSettledInStatementId(appt.getSettledInStatementId());
        v.setCheckedInAt(appt.getCheckedInAt());
        v.setCheckedInBy(appt.getCheckedInBy());
        v.setAbsentMarkedBy(appt.getAbsentMarkedBy());
        v.setAbsentMarkedAt(appt.getAbsentMarkedAt());
        v.setOperator(appt.getOperator());
        v.setCreateTime(appt.getCreateTime());
        return v;
    }

    /** 签到行解析中间结构。 */
    private static class CheckinRow {
        private InvigilatorAppointment appt;
        private Invigilator staff;
        private String result;
        private String note;
        private String error;
        private int sessionIndex;
        private BigDecimal amount;
    }
}
