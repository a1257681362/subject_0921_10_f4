package com.evops.staff.common;

/**
 * 兼职监考聘任与结算业务常量（staff-1）。
 */
public final class StaffConstants {

    private StaffConstants() {
    }

    /** 默认项目编码（与批次 project_code 缺省值对齐）。 */
    public static final String PROJECT_DEFAULT = "DEFAULT";

    // ---------- 监考人员状态 ----------
    public static final String STAFF_ENABLED = "ENABLED";
    public static final String STAFF_DISABLED = "DISABLED";

    // ---------- 监考角色（角色不同，津贴标准不同） ----------
    /** 主考（考点级槽位）。 */
    public static final String ROLE_CHIEF = "CHIEF_INVIGILATOR";
    /** 监考员（考场级槽位，随排班每考场一名）。 */
    public static final String ROLE_INVIGILATOR = "INVIGILATOR";
    /** 巡考（考点级槽位）。 */
    public static final String ROLE_PATROL = "PATROL";
    /** 备用。 */
    public static final String ROLE_BACKUP = "BACKUP";

    // ---------- 津贴标准版本状态 ----------
    public static final String VERSION_DRAFT = "DRAFT";
    public static final String VERSION_ACTIVE = "ACTIVE";
    public static final String VERSION_DISABLED = "DISABLED";

    // ---------- 聘任槽位类型 ----------
    public static final String SLOT_ROOM = "ROOM";
    public static final String SLOT_SITE = "SITE";

    // ---------- 聘任来源 ----------
    /** 随排班自动生成。 */
    public static final String SOURCE_AUTO = "AUTO";
    /** 人事手工补聘 / 指派。 */
    public static final String SOURCE_MANUAL = "MANUAL";

    // ---------- 聘任记录状态 ----------
    /** 待执行（已聘任，场次未签到）。 */
    public static final String APPOINT_PENDING = "PENDING";
    /** 已签到，计津贴。 */
    public static final String APPOINT_CHECKED_IN = "CHECKED_IN";
    /** 缺勤，不计费。 */
    public static final String APPOINT_ABSENT = "ABSENT";
    /** 取消（重排冲销或人工取消）。 */
    public static final String APPOINT_CANCELLED = "CANCELLED";

    // ---------- 聘任计酬状态 ----------
    /** 暂不计酬（待签到 / 缺勤 / 取消）。 */
    public static final String PAY_NONE = "NONE";
    /** 已计津贴（签到后）。 */
    public static final String PAY_CALCULATED = "CALCULATED";

    // ---------- 聘任版本变更类型 ----------
    /** 初聘。 */
    public static final String CHANGE_APPOINT = "APPOINT";
    /** 换人。 */
    public static final String CHANGE_REPLACE = "REPLACE";
    /** 取消。 */
    public static final String CHANGE_CANCEL = "CANCEL";
    /** 撤销误签（回退待执行，不抹版本链）。 */
    public static final String CHANGE_REVOKE = "REVOKE";

    // ---------- 签到结果 ----------
    public static final String CHECKIN_PRESENT = "PRESENT";
    /** 迟到：本版视同到场计酬，需要扣减走结算调整单。 */
    public static final String CHECKIN_LATE = "LATE";
    /** 缺勤：不计费。 */
    public static final String CHECKIN_ABSENT = "ABSENT";

    // ---------- 结算单状态 ----------
    /** 草稿：可重复出单覆盖。 */
    public static final String SETTLE_DRAFT = "DRAFT";
    /** 已确认锁定：要改走调整单。 */
    public static final String SETTLE_CONFIRMED = "CONFIRMED";

    // ---------- 结算明细类型 ----------
    public static final String ITEM_SESSION = "SESSION";

    // ---------- 调整类型 ----------
    /** 补发。 */
    public static final String ADJUST_SUPPLEMENT = "SUPPLEMENT";
    /** 扣减。 */
    public static final String ADJUST_DEDUCT = "DEDUCT";
}
