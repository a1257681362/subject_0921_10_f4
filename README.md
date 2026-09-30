# EVOPS 基础工作区

这是 springboot的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

> 交付副本约定路径为根目录 `..\..\docs\schema\evops.sql`（即 `/docs/schema/evops.sql`）。
> 当前环境根目录不可写，副本放在工作区内 `docs/schema/evops.sql`，与 `src/main/resources/schema.sql` 保持一致。

## 考试机构考场与准考证建档工作区（exam-1）

覆盖四步：**报名 → 编排 → 发证 → 查档**。所有接口在 Shiro 保护下，使用 HTTP Basic 认证：
`curl -u bootstrap:bootstrap ...`；经办人通过请求头 `X-Operator` 记录（缺省 `system`）。

约定：
- 所有响应统一为 `{success, code, message, data}`；失败返回可读状态码与中文原因（含 401/404），不返回空白。
- **批量动作分两步**：先调 `*/preview` 看影响范围（可处理/跳过逐条列出），确认后再调执行接口。
- **阶段结果**（`t_stage_result`）与**最终结论**（`t_final_conclusion`）分表保存。
- **版本可追溯**：快照只追加；版本选择只切当前标记；回退按快照还原并生成新的 ROLLBACK 版本，原版本与事件链（CREATE/SELECT/ROLLBACK）都保留。

### 1. 建档：考点 / 考场 / 批次

- `POST /api/sites`、`GET /api/sites`：考点（编码、名称、位置、联系人）。
- `POST /api/rooms`、`GET /api/rooms?siteId=`：考场（考场号、楼栋、楼层、**容量**、位置描述）。
- `POST /api/batches`、`GET /api/batches`：考试批次（归属考点、科目、考试时间）。

### 2. 报名（名册挂进批次，支持勘误留痕）

- `POST /api/registrations/import/preview` → `POST /api/registrations/import`：批量导入，报名号撞号**整批拒收**。
- `POST /api/registrations/{id}/corrections`：勘误。旧值写入 `t_registration_correction`，不抹历史；主表记新值并累加更正次数。
- `GET /api/registrations/{id}/corrections`：查更正痕迹。
- `GET /api/registrations/search/by-name?name=`：按姓名查名册（包含匹配，重名全返回）。
- `GET /api/registrations?batchId=`：批次名册。

### 3. 编排（按批次生成准考证，一人一条）

- `POST /api/arrange/preview` → `POST /api/arrange`：
  按**同校混编算法**排座（同校按容量比例摊到多个考场、考场内交错入座），证号规则 `ZK + 批次编码 + 4 位流水`。
  证号唯一、批次内（考场+座位）唯一，**撞号直接拒收**；容量不足整批拒收、不落半条；已编排批次不允许重复编排（要改走整批重排）。
- 报名导入可带 `school`（所属学校）；未填学校按"未知"处理，不参与同校相邻判定。学校字段可勘误（fieldName=`SCHOOL`）。

### 4. 发证（打证、发放两步，各留经办与钟点）

- 打证：`POST /api/tickets/print/preview` → `POST /api/tickets/print`（PENDING→PRINTED，记 printed_by/printed_at）。
- 发放：`POST /api/tickets/issue/preview` → `POST /api/tickets/issue`（PRINTED→ISSUED，记 issued_by/issued_at）。
- 目标可用 `{"batchId":...}` 整批，或 `{"ticketNos":[...]}` 指定；状态不能跳步，重复操作逐条跳过并给原因。
- 证书（证面）打印不在本版范围，下版再做。

### 5. 查档（证号、考场、座位一人一条）

- `GET /api/query/by-ticket-no?ticketNo=`：按证号精确查。
- `GET /api/query/by-name?name=`：按姓名查（含重名）。
- `GET /api/query/by-batch?batchId=`：按批次列全部。
- 注意：查询参数含中文时需做 URL 编码（`curl -G --data-urlencode "name=张三"`）。

### 6. 阶段结果 / 最终结论

- `GET  /api/batches/{id}/progress/stages`：各阶段结果。
- `POST /api/batches/{id}/progress/conclusion`、`GET .../conclusion`：生成 / 查看最终结论（每批次一条）。
- `GET  /api/batches/{id}/progress/overview`：阶段结果与最终结论作为两个字段分开返回。

### 7. 工作区版本（版本选择 / 状态回退可追溯）

- `POST /api/workspace/versions?label=&remark=`：创建快照（自动成为当前版本）。
- `GET  /api/workspace/versions`、`GET /api/workspace/versions/current`、`GET /api/workspace/versions/{no}`。
- `POST /api/workspace/versions/{no}/select`：版本选择（只切当前标记，业务数据不变）。
- `POST /api/workspace/versions/{no}/rollback?remark=`：按该版本快照还原，生成新的 ROLLBACK 版本，原版本保留。
- `GET  /api/workspace/versions/events?versionNo=`：CREATE/SELECT/ROLLBACK 事件链。

## 出分等级与成绩复核工作区（grade-1）

把"出分定级"和"成绩复核"两段在 evops-base 串成一条线：**卷面分按分数段落等级、规则带版本 → 窗口跟批次配置 → 窗口期提复核 → 结论回写通知、改分留痕**。两个岗共用同一套规则版本与窗口口径，不再打架。本批次先跑助考项目（`projectCode=ZHUKAO`）。

### 8. 批次项目与复核窗口（不写死）

- 建批次 `POST /api/batches` 可带 `projectCode`（缺省 `DEFAULT`）与 `reviewStartAt` / `reviewEndAt`。
- `POST /api/batches/{id}/review-window`：单独配置 / 调整窗口（起止必须成对、起早于止）。
- `GET  /api/batches/{id}/review-window`：窗口起止与当前状态 `NOT_CONFIGURED / NOT_OPENED / OPEN / CLOSED`，附各状态复核计数。

### 9. 等级规则模块（规则 + 版本 + 状态）

- `POST /api/grade-rules`、`GET /api/grade-rules?projectCode=`：规则按项目建档（可带 `subject` 按科目细分，留空为项目通用），`maxScore` 为满分。
- `POST /api/grade-rules/{ruleId}/versions`：建草稿版本，提交分数段 `bands:[{minScore,maxScore,grade}]`（左闭右开；段段相连不重叠不缺口；首段从 0 起、末段到满分止，否则拒收）。
- `GET  /api/grade-rules/{ruleId}/versions`、`GET /api/grade-rules/versions/{versionId}`。
- `POST /api/grade-rules/versions/{versionId}/publish`：**DRAFT→ACTIVE**，发布即冻结分数段，并自动把同规则旧生效版置 **DISABLED**（同规则同时刻仅一版生效）。
- `POST /api/grade-rules/versions/{versionId}/disable`：人工停用生效版。
- `POST /api/grade-rules/versions/{versionId}/trial`：`{"score":72}` 按版本试算等级，不写成绩。
- 定级只认项目当前 ACTIVE 版本（优先科目精确匹配，其次通用规则）；成绩行永久记下 `ruleVersionNo`，规则以后升级不改变历史口径。

### 10. 成绩录入与定级

- `POST /api/scores/import/preview` → `POST /api/scores/import`：按批次批量录卷面分（按报名号定位名册，须属于本批次）。预检逐条列可录入/跳过与定级结果、规则版本；执行时任一不通过**整批拒收、不落半条**，已录成绩不允许重复导入（改分必须走复核）。
- `GET  /api/scores/batch/{batchId}`、`GET /api/scores/{id}`。
- `GET  /api/scores/change-logs?batchId=`、`GET /api/scores/{id}/change-logs`：改分前后留痕（只追加）。

### 11. 成绩复核（窗口校验 / 超窗自动关闭 / 结论回写通知）

- `POST /api/reviews/apply`：`{"scoreId":...}` 或 `{"batchId":...,"regNo":...}` 提交复核。仅窗口 **OPEN** 可提交；未配置 / 未开始 / 已关闭分别返回可读原因（`REVIEW_WINDOW_*`）；同一成绩处理中不可重复提交；成绩置 `REVIEWING`。
- `POST /api/reviews/{id}/conclusion`：`{"approved":true,"newScore":85,"conclusion":"..."}` 复核通过——按**原定级规则版本**重算等级、改分，前后分数/等级写入复核单和改分日志，并发站内通知；`{"approved":false,"conclusion":"..."}` 驳回维持原分，同样通知。
- 超窗自动关闭：窗口（批次配置的 `reviewEndAt`）过后仍 `SUBMITTED` 的申请，定时任务（每分钟，`evops.review.close-cron` 可调）自动置 `CLOSED`、成绩解锁、通知学员；也可 `POST /api/reviews/close-expired` 手动立即执行。
- `GET /api/reviews?status=`、`GET /api/reviews/batch/{batchId}?status=`、`GET /api/reviews/{id}`。
- 通知：`GET /api/notifications?regNo=&unreadOnly=`、`POST /api/notifications/{id}/read`（本版站内落库，短信等外发渠道下版再接）。

## 同校混编整改工作区（mix-1）

针对"同校学员相邻而坐、抄袭"的监考通报：在现有编排上加同校错开约束，同校按比例摊到多个考场，座次表可打印，重排整批进行不留半新半旧，并给出同校相邻统计供巡考抽查。种子与座次由 **evops-base** 落库保存。

### 12. 名册学校信息

- 报名导入 / 单项中 `school` 为所属学校（可空，空按未知处理，不计同校相邻）；`GET /api/registrations?batchId=` 返回含 `school`。
- 学校可勘误：`POST /api/registrations/{id}/corrections`，`fieldName=SCHOOL`。

### 13. 混编算法模块（模块 + 版本 + 状态）

与等级规则同一套版本模式：算法按项目建档（`projectCode`，缺省 `DEFAULT`），版本 **DRAFT→ACTIVE→DISABLED**，发布即冻结参数 JSON，同模块同时刻仅一版 ACTIVE，停用不删、历史编排保留版本号。

- `POST /api/mix-algorithms`、`GET /api/mix-algorithms?projectCode=`、`GET /api/mix-algorithms/{id}`。
- `POST /api/mix-algorithms/{id}/versions`：建草稿，参数为
  `{"strategy":"SCHOOL_SPREAD","minSchoolGap":1,"spreadRooms":true,"maxSameSchoolPerRoom":null,"tries":40}`
  （间隔须 ≥1、tries 1~1000，非法整批拒收）；`GET .../versions`、`GET /api/mix-algorithms/versions/{versionId}`。
- `POST /api/mix-algorithms/versions/{versionId}/publish`（旧 ACTIVE 自动 DISABLED）、`POST .../disable`。
- 编排取批次所属项目的当前 ACTIVE 版本；项目无生效版本时用内置默认参数（结果中标 `BUILTIN_DEFAULT`）。

### 14. 编排 / 整批重排 / 抽查统计

- 首次编排仍为 `POST /api/arrange/preview` → `POST /api/arrange`，请求可带 `seed`（不传由系统取确定性种子）。预检与执行共用同一方案，返回 `mixVersionNo / seed / roomCount / sameSchoolAdjacentCount / adjacentPairs`。
- **整批重排**：`POST /api/arrange/rearrange/preview` → `POST /api/arrange/rearrange`。
  单一事务内删旧证、按新方案整批重建；要么全新要么整批回滚，**不留半新半旧**。
  证一旦打印 / 发放即锁定座次（`REARRANGE_TICKETS_USED`），防止纸面与座次不符。
- 编排记录（种子 + 座次由 evops-base 落 `t_arrangement_run`，只追加、重排只切 `is_current`）：
  `GET /api/arrange/runs?batchId=`（列表不带座次大字段）、`GET /api/arrange/runs/current?batchId=`、`GET /api/arrange/runs/{id}`（含座次快照与相邻明细 JSON）。
- 同校相邻口径：同一考场内座位号连续且两人同校；返回总对数与逐对明细（考场号、座位 A/B、学校、两人报名号姓名），供巡考抽查。

### 15. 座次表导出打印

- `GET /api/arrange/seating-chart?batchId=`：打印用 HTML，每个考场单独一页（浏览器直接打印 / 另存 PDF），含考场号、座位号、准考证号、报名号、姓名、学校与签到栏，抬头标注混编版本、种子与同校相邻对数。
- `GET /api/arrange/seating-chart.csv?batchId=`：同内容 CSV（UTF-8 BOM，Excel 直开）。
- 未编排批次导出返回 `ARRANGEMENT_RUN_NOT_FOUND`。

> 新表 `t_mix_algorithm / t_mix_algorithm_version / t_arrangement_run` 已纳入工作区快照与回退；schema 版本号 `mix-1`。

## 兼职监考聘任与结算工作区（appoint-1）

把"聘任和结算"做进 evops-base：**监考随排班生成聘任 → 场次结束签到计津贴（角色不同标准不同）→ 缺勤不计费 → 换人留替换记录 → 月底一键出结算单（人事看全量、本人看自己）→ 确认锁定、要改走调整**。兼职老师对费有据可查，不再和人事窗口各执一本账。聘任记录分**模块（主表）/ 版本（只追加）/ 状态（当前版本）**三段。

视图口径：所有接口仍走 Shiro HTTP Basic；请求头 **`X-Self-Code=<监考人员编码>`** 即"本人视图"（只返回本人聘任/版本/替换/结算明细），不传为人事全量视图；经办人仍由 `X-Operator` 记录。

### 16. 监考人员与津贴标准（角色不同标准不同）

- `POST /api/invigilators`、`GET /api/invigilators`：兼职监考人员（编码、姓名、默认角色 `CHIEF 主考 / INVIGILATOR 监考 / PATROL 巡考`、`staffKind`）。
- `POST /api/invigilators/allowance-rates`、`GET .../allowance-rates?projectCode=&roleCode=`：按**项目 + 角色 + 生效区间**配置单场津贴；区间重叠或到期缺标准在生成聘任时报 `RATE_AMBIGUOUS / RATE_NOT_CONFIGURED`。
- 津贴口径在**生成聘任（或换人接任）时冻结**进聘任版本（rateId/rateCode/金额），以后调价不改历史聘任。

### 17. 监考随排班生成聘任（两步预检）

- `POST /api/appointments/shifts/preview` → `POST /api/appointments/shifts`：请求 `{batchId, shiftStart?, shiftEnd?, assignments:[{roomId, roleCode?, invigilatorCode}]}`。
  按批次把监考排进考场角色场次，**一条在岗排班生成一条聘任**（`t_invigilation_shift` + `t_appointment` + 首版 CREATE `t_appointment_version`）。
  - 预检逐条列可生成 / 跳过与命中标准、金额；同考场可排多名同角色监考；同人同批次重复排班、同人多岗、考场不属于本考点等结构性问题**整批拒收**；已排班场次幂等跳过。
- 聘任号 `AP + 批次编码 + 4 位流水`；排班与聘任 1:1。

### 18. 聘任记录版本 / 状态（只追加）

- 状态机：**APPOINTED 已聘任 → CHECKED_IN 已签到（计费）**；旁路 **ABSENT 缺勤（不计费）/ REPLACED 已换人 / CANCELLED 已取消**。
- `POST /api/appointments/{id}/check-in`（可带 `signInAt`，缺省当前时间）：**场次结束签到后才计津贴**（billable=true），金额沿用聘任时冻结口径，结算周期按签到月归属。
- `POST /api/appointments/{id}/absent?reason=`：未签到记缺勤，**缺勤不计费**；已签到（已计费）不能改记缺勤。
- `POST /api/appointments/{id}/cancel?reason=`：场次取消，不计聘任计费（已签到计费的不能取消）。
- 每次状态变化**追加一版** `t_appointment_version`，主表只切当前版本号与状态；`GET /api/appointments/{id}/versions` 查完整版本链。

### 19. 换人（留替换记录）

- `POST /api/appointments/{id}/replace`：`{toInvigilatorCode, reason}`。
  一次换人：原持聘人追加 **REPLACED 版（不计本场费）**、接任者追加 **APPOINTED 新版**（按接任角色/场次日期重新冻结津贴），写一条 `t_appointment_replacement`（谁→谁、原因、经办、钟点），排班持聘人同步更新；接任者本批次已有在岗聘任则拒收。
- `GET /api/appointments/replacements?batchId=`、`GET /api/appointments/{id}/replacements`：替换记录（本人视图只返回涉及本人者）。

### 20. 月底一键结算单（人事全量 / 本人自己；确认锁定、调整留痕）

- `POST /api/settlements/preview` → `POST /api/settlements`：请求 `{month:"yyyy-MM"（缺省当月）}`。
  按**签到月**汇总区间内已签到（CHECKED_IN / billable）且未锁定的聘任，**一人一行**（场次、金额合计）。先预检看全量口径，确认生成 **DRAFT 草稿**；草稿可重复生成按最新签到重算。
- `POST /api/settlements/{id}/confirm`：**确认后锁定**——计费聘任置 `settled` 并挂结算单（之后不能再签到/换人/取消），已确认月份不能重新生成结算单。
- 查询：`GET /api/settlements[?month=]`；`GET /api/settlements/{id}`（人事返回全部明细，带 `X-Self-Code` 仅返回本人那一行，响应标 `view=HR_ALL/SELF`）；`GET /api/settlements/my-items[?month=]` 本人跨单只看自己；`GET /api/appointments[?batchId=&status=&invigilatorCode=]` 本人视图强制只看本人。
- **要改走调整**：`POST /api/settlements/{id}/adjustments` `{invigilatorCode, adjustAmount(可正可负), reason}`，只追加 `t_settlement_adjustment`，本人明细与结算单应付额累加变动；`GET /api/settlements/{id}/adjustments` 查台账。未确认草稿不接受调整（直接重算即可）。

> 新表 `t_invigilator / t_invigilator_allowance_rate / t_invigilation_shift / t_appointment / t_appointment_version / t_appointment_replacement / t_settlement / t_settlement_item / t_settlement_adjustment` 已纳入工作区快照与回退；schema 版本号 `appoint-1`。
