package com.evops.staff.vo;

import com.evops.staff.entity.InvigilatorSettlement;
import com.evops.staff.entity.InvigilatorSettlementAdjust;
import com.evops.staff.entity.InvigilatorSettlementItem;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 结算单视图：单头 + 场次明细 + 调整明细。人事看全量、本人看自己时同一结构。
 */
@Data
public class SettlementView {
    private InvigilatorSettlement statement;
    private List<InvigilatorSettlementItem> items = new ArrayList<>();
    private List<InvigilatorSettlementAdjust> adjusts = new ArrayList<>();
}
