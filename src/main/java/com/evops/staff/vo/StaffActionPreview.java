package com.evops.staff.vo;

import com.evops.exam.vo.AffectedItem;
import lombok.Data;

import java.util.List;

/**
 * 批量动作预检 / 执行结果（签到、缺勤标记）：逐条列可处理 / 跳过及原因。
 */
@Data
public class StaffActionPreview {
    private String action;
    private int totalScanned;
    private int actionableCount;
    private int skipCount;
    private String summary;
    private List<AffectedItem> items;
}
