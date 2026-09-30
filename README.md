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

## 兼职监考聘任与结算工作区（staff-1）

把"兼职老师月底拿小本本对监考费、跟人事窗口吵架"这段做进 evops-base：**监考随排班生成聘任 → 场次结束签到计津贴（角色不同标准不同）→ 换人留替换记录、缺勤不计费 → 月底一键出结算单（人事看全量、本人看自己）→ 确认锁定，要改走调整单**。

视角约定：HTTP Basic 仍是 `bootstrap/bootstrap`；**人事全量**直接调接口；**本人视角**请求头带 `X-Staff-No: 工号`（也支持 `?staffNo=` 参数），聘任与结算单都只出本人的，看他人单据返回 `SETTLEMENT_FORBIDDEN`。

### 16. 监考人员与角色津贴标准（模块 + 版本 + 状态）

- `POST /api/invigilators`、`GET /api/invigilators?status=&keyword=`、`GET /api/invigilators/{id}`：监考人员花名册，`availableRoles` 为可担任角色清单（缺省仅 `INVIGILATOR`），停用后历史聘任保留。
- 角色：`CHIEF_INVIGILATOR` 主考、`INVIGILATOR` 监考员、`PATROL` 巡考、`BACKUP` 备用；**角色不同标准不同**。
- `POST /api/invigilator-stipends`、`GET /api/invigilator-stipends?projectCode=&roleCode=`：按**项目+角色**建津贴标准模块（同项目同角色仅一套）。
- `POST /api/invigilator-stipends/{id}/versions`：草稿版本，`{ratePerSession, extraPerSession, effectiveFrom}`（金额不能为负）；`GET .../versions`、`GET /api/invigilator-stipends/versions/{versionId}`。
- `POST .../versions/{versionId}/publish`（**DRAFT→ACTIVE**，同模块旧生效版自动 DISABLED，发布即冻结金额）、`POST .../disable`。计酬只认项目+角色当前 ACTIVE 版本；未建档 / 无生效版给可读错误，不隐式兜底，避免错发。

### 17. 聘任记录模块（聘任记录 + 版本 + 状态）

- **监考随排班生成聘任**：编排 / 整批重排落库后，按本次实际使用考场自动生成监考员聘任槽位；整批重排后不再使用考场上的**待执行**槽位自动取消（留 CANCEL 版本），已签到 / 缺勤记录保留。
- 人事按批次两步生成（可带 roomId→工号 派班表）：`POST /api/appointments/generate/preview` → `POST /api/appointments/generate`；预检逐条列 CREATE / KEEP / CANCEL 与派班校验结果。
- `POST /api/appointments/manual`：手工补聘主考 / 巡考等考点级槽位（`roomId` 留空）或指定考场监考员。
- 状态：`PENDING` 待执行 → `CHECKED_IN` 已签到（计酬）/ `ABSENT` 缺勤（不计费）/ `CANCELLED` 取消；计酬状态 `NONE / CALCULATED`。
- **换人留替换记录**：`POST /api/appointments/{id}/replace` `{staffNo, reason}`——只追加 `REPLACE` 版本（记 from/to），主表切当前版本、`rowVersion` 递增；已签到计酬的须先撤销签到，已进结算单的锁定拒绝。取消 `POST /api/appointments/{id}/cancel`。
- `GET /api/appointments?batchId=&status=&payStatus=`（本人带头只看自己）、`GET /api/appointments/{id}`、`GET /api/appointments/{id}/versions`（APPOINT→REPLACE/CANCEL/REVOKE 版本链）。
- 一人在同一批次只能有一个未结束聘任（`INVIGILATOR_BUSY`），角色须在其可担任清单内。

### 18. 场次签到计津贴（缺勤不计费）

- 两步：`POST /api/appointments/checkin/preview` → `POST /api/appointments/checkin`，批量 `items:[{appointmentId, result:PRESENT/LATE/ABSENT, note}]`。
- **场次结束签到后计津贴**：按聘任上冻结的角色标准快照计酬；同批次第 N 场 = 单场标准 +(N-1)×超场补贴；金额、标准版本号、场次序号回写聘任。
- `ABSENT` 缺勤：状态置 ABSENT、金额 0、不计酬（不出现在结算单）；`LATE` 迟到本版视同到场，需要扣减走结算调整单。
- 重复签到逐条跳过并给原因；误签可 `POST /api/appointments/{id}/revoke-checkin?reason=`（未进结算单方可，回退待执行并留 REVOKE 版本）。
- 排班时标准尚未发布不阻塞槽位生成（标准快照留空），签到时自动补快照；仍无生效版则 `STIPEND_RATE_MISSING` 拒绝并提示先发布标准。

### 19. 月底结算单（一键出单、确认锁定、调整改账）

- 一键两步：`POST /api/settlements/generate/preview` → `POST /api/settlements/generate`，body `{"period":"202609"}`；按批次**考试日期归月**，把当月 `CALCULATED` 聘任按人汇总，**一人一月一条**结算单，明细一行一场（批次/考场/角色/标准版本号/金额快照）。
- 预检按人列 CREATE 新建 / REBUILD 草稿重建 / LOCKED 已确认跳过；草稿可重复出单覆盖（旧明细删除、聘任挂单标记解除后重建），**已确认锁定的单出单跳过**。
- `POST /api/settlements/{id}/confirm`：**DRAFT→CONFIRMED 锁定**；锁定后聘任侧同步冻结（换人 / 撤签返回 `APPOINTMENT_SETTLED_LOCKED`）。
- **要改走调整**：`POST /api/settlements/{id}/adjusts` `{adjustType:SUPPLEMENT/DEDUCT, amount, reason, appointmentId?}`——只追加调整单，补发为正、扣减为负，实时累加 `adjustAmount/totalAmount`，原场次明细不动。
- 查询：`GET /api/settlements?period=&status=`（人事全量 / 本人带头只列自己）、`GET /api/settlements/{id}`（单头 + 场次明细 + 调整明细；本人只准看自己）。

> 新表 `t_invigilator / t_invigilator_stipend(_version) / t_invigilator_appointment(_version) / t_invigilator_checkin / t_invigilator_settlement(_item/_adjust)` 已纳入工作区快照与回退；schema 版本号 `staff-1`。
