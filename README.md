# EVOPS 基础工作区

这是 springboot 的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

`docs/schema/evops.sql` 是交付副本，与工作区 `src/main/resources/schema.sql` 保持一致（骨架原约定的
`../../docs/schema/evops.sql` 在当前环境根目录无写权限，故置于工作区内）。

## 博物馆文物修复台账（museum-1）

在骨架上新增的业务模块，**不含票务模块**。

### 数据模型

| 表 | 说明 |
| --- | --- |
| `t_relic` | 文物档案：文物编号、材质、年代、来源、保存等级（ONE/TWO/THREE）；撤销只置 `REVOKED` 状态 |
| `t_relic_intake` | 入藏登记：尺寸（高宽厚、重量）、库位、入藏时间，一件文物一条 |
| `t_relic_photo` | 入藏关联照片，一张照片一行 |
| `t_repair_project` | 修复项目：项目编号、委托人、修复方案；状态机见下 |
| `t_repair_stage` | 修复阶段结果，阶段号项目内递增 |
| `t_repair_acceptance` | 验收记录，一个项目一条 |
| `t_relic_loan` | 借出/归还单，一条记录贯通一次借还 |
| `t_relic_ledger` | 台账流水：入藏/借出/归还/修复/验收/撤销/作废全部追加留痕 |
| `t_bootstrap_lock` | 起步幂等锁，固定单行 id=1 |

约束：文物编号 `uk_relic_no`、项目编号 `uk_project_no` 数据库层唯一（含已撤销/已作废记录）；
撤销与作废均不删除任何数据。

### 状态链路

- 修复项目：`PLANNED → REPAIRING → REPAIRED → ACCEPTED`；`PLANNED/REPAIRING` 可 `CANCELLED`。
- 借出：`LENT → RETURNED`（在原单上更新，不新增记录）。
- 跨业务校验：文物借出未还不能重复借出、不能开工修复；有未闭环借还/项目的文物不能撤销。

### 主要接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/bootstrap` | **博物馆文物修复档案起步**：首次播种一条走完全链路的有效样本 |
| `GET /api/bootstrap` | 只读查看起步样本（未起步为 null） |
| `POST /api/relics` | 文物建档（编号重复返回失败） |
| `GET  /api/relics?status=&keyword=` | 档案列表 |
| `GET  /api/relics/{id}` | 完整档案：档案 + 入藏照片 + 借还 + 项目 + 台账流水 |
| `POST /api/relics/{id}/intake` | 入藏登记（照片/尺寸/库位，至少一张照片） |
| `POST /api/relics/{id}/revoke` | 撤销（只改状态，记录保留） |
| `POST /api/loans` / `POST /api/loans/{id}/return` | 借出 / 归还 |
| `POST /api/repair-projects` | 修复立项（委托人、方案，项目编号唯一） |
| `POST /api/repair-projects/{id}/start` | 开工 |
| `POST /api/repair-projects/{id}/stages` | 登记阶段结果（可多次） |
| `POST /api/repair-projects/{id}/finish` | 修复完成 |
| `POST /api/repair-projects/{id}/accept` | 验收（PASS/CONDITIONAL） |
| `POST /api/repair-projects/{id}/cancel` | 作废 |

### 起步幂等

`POST /api/bootstrap` 首次调用播种样本 `WW-2026-0001`（入藏 `RU-2026-0001` → 借出/归还
`LO-2026-0001` → 修复项目 `RP-2026-0001`，含两个修复阶段与验收）。

幂等保证：播种与 `t_bootstrap_lock`（单行、CHECK id=1）在同一事务提交；重复请求直接回读同一行返回
`created=false`，不会产生第二条文物/项目/台账记录；并发请求靠主键冲突整体回滚，已通过 8 并发测试验证。

## 文物多角度影像归档（museum-2）

在 museum-1 之上新增的影像子模块。正面 `FRONT`、背面 `BACK`、底部 `BOTTOM`、细部 `DETAIL`
照片按**拍摄批次**归档到「修复项目 + 修复阶段」，走真实 `multipart/form-data` 二进制上传。

### 数据模型

| 表 | 说明 |
| --- | --- |
| `t_image_blob` | 影像物理文件，按内容 SHA-256 寻址（`ab/cd/<sha>`），同一文件全局只有一份；含实测宽高与 EXIF 机型 |
| `t_image_batch` | 拍摄批次：一次批量上传一行，挂 project + stageNo，记录归档/去重/驳回计数 |
| `t_image_archive` | 影像版本：一张照片一个视角一个版本一行，引用 blob；唯一约束 `(项目,视角,版本号)` 与 `(项目,视角,blob)` |
| `t_repair_permission_state` | 修复权限闸单例状态（CHECK id=1，服务层惰性建行，不进 schema MERGE） |
| `t_repair_permission_event` | 收紧/放宽事件，只追加 |

物理文件默认写入 `./data/image-store`（`evops.image.store-dir` 可配）；分辨率下限
`evops.image.min-width/min-height`（生产 800x600，测试 100x100）。

### 核心规则

- **上传校验**：JDK ImageIO 实测分辨率（不达标逐张驳回）；拍摄时间、摄影者按
  EXIF（DateTimeOriginal / Artist）优先、表单逐项字段、批次级字段的顺序兜底，二者最终必须有值，
  拍摄时间不得晚于上传时刻；仅受理 JPEG/PNG。
- **重复上传只保留一份引用**：相同字节物理只存一份（内容寻址幂等落盘 + blob 唯一索引）；
  `(项目,视角,文件)` 已有引用时返回 `DEDUPED` 并回指既有版本号，不新增版本。已作废引用的同字节
  文件不允许重新归档。
- **不同修复阶段必须形成新版本**：版本号在 `(项目,视角)` 内跨阶段递增，作废也不回收；
  同一阶段同一视角不允许用不同文件再拍（需登记新的修复阶段）；不允许向更早阶段补拍。
- **作废不删原文件**：批次作废把批次与其影像版本置 `VOIDED`（原因/时间留痕），blob 行与磁盘文件
  永久保留；版本历史仍可查，仅"当前有效版本"下载失效。作废动作追加 `IMAGE_VOID` 台账流水。
- **批量上传逐项结果**：允许部分成功。响应给出 `archivedCount/dedupedCount/rejectedCount` 与每张照片的
  `{index,fileName,viewType,status,versionNo,sha256,reason}`；岗位无权、权限收紧、项目/阶段不存在等
  批次级问题整体失败、不落任何数据。成功归档追加 `IMAGE_ARCHIVE` 台账流水。
- **下载按岗位脱敏**：岗位由请求头 `X-Staff-Role` 声明（缺省/未知按最严格 `VIEWER`）。图像字节不脱敏，
  只遮蔽随附元数据：

  | 字段 | RESTORER | ARCHIVIST | CURATOR | VIEWER |
  | --- | --- | --- | --- | --- |
  | 摄影者 | 完整 | 完整 | 完整 | 遮蔽 |
  | 拍摄时间 | 完整时刻 | 完整时刻 | 仅日期 | 仅日期 |
  | 相机机型 | 可见 | 可见 | 遮蔽 | 遮蔽 |
  | 内容指纹 | 可见 | 可见 | 可见 | 遮蔽 |

  权限：上传限 `RESTORER/ARCHIVIST`；作废限 `ARCHIVIST/CURATOR`；收紧/放宽权限仅 `CURATOR`。
- **权限收紧闸**：`POST /api/repair-permission/tighten` 后影像上传在预检阶段整体停止推进（报错带回原因），
  但已确认的版本归档、原文件与下载/查询能力全部保留；`relax` 后恢复。每次收紧/放宽在
  `t_repair_permission_event` 只追加一行。

### 影像接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/image-archives/batches` | multipart 批量上传（fields: projectId、stageNo、files、viewTypes[]、可选 photographer/shotAt 与 photographers[]/shotTimes[]） |
| `POST /api/image-archives/batches/{id}/void` | 批次作废（不删文件） |
| `GET  /api/image-archives/batches/{id}` | 批次详情（按岗位脱敏） |
| `GET  /api/image-archives/projects/{pid}/versions?viewType=` | 版本历史（含已作废，脱敏） |
| `GET  /api/image-archives/projects/{pid}/views/{viewType}/file` | 下载当前有效版本原始字节（元数据经自定义响应头按岗位下发） |
| `GET  /api/repair-permission` | 查看闸状态与收紧/放宽事件 |
| `POST /api/repair-permission/tighten` / `relax` | 收紧 / 放宽（仅 CURATOR） |

### 测试

```bash
mvn test
```

- `ImageArchiveApiTest`：四视角批次、分辨率/拍摄时间/摄影者校验、同文件物理只存一份、跨阶段新版本、
  同阶段异文件驳回、逐张失败原因与部分成功、作废留文件、VIEWER/ARCHIVIST 脱敏、原始字节下载。
- `RepairPermissionGateTest`：收紧停传但旧版本仍可查可下载、放宽恢复、仅 CURATOR 可调、事件只追加。
- `BootstrapApiTest`：起步两次只产生一条全链路样本，台账动作顺序完整。
- `ConcurrentBootstrapTest`：8 并发同时起步，仅一次播种，全部命中同一条样本。
- `MuseumLedgerRuleTest`：编号唯一、入藏不重复、借还/修复/验收状态机不可跳步、撤销不抹除记录。
