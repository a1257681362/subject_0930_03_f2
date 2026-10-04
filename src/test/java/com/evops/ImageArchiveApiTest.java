package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文物多角度影像归档规则验收：
 * 四视角批次、分辨率/拍摄时间/摄影者校验、内容去重只存一份、跨阶段新版本、
 * 逐张失败原因、作废不删原文件、按岗位脱敏、下载原始字节。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:image-rules;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImageArchiveApiTest extends ImageApiTestSupport {

    private static final String UPLOADER = "ARCHIVIST";

    // ----------------------------------------------------------------
    // 1. 正常四视角批次：全部 ARCHIVED，版本从 1 起
    // ----------------------------------------------------------------
    @Test
    void four_views_batch_archived() throws Exception {
        long pid = createRepairProject("IMG-001", "IMG-P-001");

        MockMultipartHttpServletRequestBuilder req = batchRequest(pid, 1, "摄影师甲", SHOT_AT);
        String[] views = {"FRONT", "BACK", "BOTTOM", "DETAIL"};
        for (String v : views) {
            req.file(jpgPart(v + ".jpg", jpeg(200, 150, new Color(v.hashCode()))));
            req.param("viewTypes", v);
        }
        JsonNode data = dataOf(performUpload(req, UPLOADER));

        assertEquals(4, data.get("archivedCount").asInt());
        assertEquals(0, data.get("dedupedCount").asInt());
        assertEquals(0, data.get("rejectedCount").asInt());
        for (int i = 0; i < 4; i++) {
            JsonNode item = data.get("items").get(i);
            assertEquals("ARCHIVED", item.get("status").asText());
            assertEquals(1, item.get("versionNo").asInt());
        }

        JsonNode dossier = dataOf(mockMvc.perform(get("/api/relics/" + findRelicId("IMG-001")))
                .andExpect(status().isOk()).andReturn());
        boolean ledgerHit = false;
        for (JsonNode e : dossier.get("ledger")) {
            if ("IMAGE_ARCHIVE".equals(e.get("action").asText())) {
                ledgerHit = true;
            }
        }
        assertTrue(ledgerHit, "台账应追加 IMAGE_ARCHIVE 流水");
    }

    // ----------------------------------------------------------------
    // 2. 同一文件重复上传：物理只存一份，引用回指既有版本（DEDUPED）
    // ----------------------------------------------------------------
    @Test
    void same_file_deduped_to_one_blob() throws Exception {
        long pid = createRepairProject("IMG-002", "IMG-P-002");
        byte[] front = jpeg(200, 150, Color.RED);

        JsonNode first = uploadOne(pid, 1, "FRONT", "front-a.jpg", front, "摄影师乙", SHOT_AT);
        assertEquals("ARCHIVED", first.get("status").asText());
        assertEquals(1, first.get("versionNo").asInt());
        long blobsAfterFirst = blobMapper.selectCount(null);

        // 完全相同字节再传（不同文件名）：DEDUPED，不新增版本
        JsonNode second = uploadOne(pid, 1, "FRONT", "front-copy.jpg", front, "摄影师乙", SHOT_AT);
        assertEquals("DEDUPED", second.get("status").asText());
        assertEquals(1, second.get("versionNo").asInt());
        assertTrue(second.get("reason").asText().contains("回指版本号 v1"));
        assertEquals(blobsAfterFirst, blobMapper.selectCount(null), "物理 blob 不得增加");
        String sha = first.get("sha256").asText();
        assertTrue(blobMapper.selectList(null).stream()
                .anyMatch(b -> b.getSha256().equals(sha)
                        && blobStorage.existsOnDisk(b.getStoragePath())), "该内容物理文件应在仓库中且仅一条 blob 行");
    }

    // ----------------------------------------------------------------
    // 3. 不同修复阶段必须形成新版本；下载解析到最新有效版本
    // ----------------------------------------------------------------
    @Test
    void new_stage_forces_new_version_and_download_latest() throws Exception {
        long pid = createRepairProject("IMG-003", "IMG-P-003");

        JsonNode v1 = uploadOne(pid, 1, "FRONT", "f1.jpg",
                jpeg(200, 150, Color.GREEN), "摄影师丙", SHOT_AT);
        assertEquals(1, v1.get("versionNo").asInt());

        addStage(pid, 2, "补配");
        JsonNode v2 = uploadOne(pid, 2, "FRONT", "f2.jpg",
                jpeg(200, 151, Color.BLUE), "摄影师丙", "2026-09-02T11:00:00");
        assertEquals(2, v2.get("versionNo").asInt());

        JsonNode versions = dataOf(mockMvc.perform(get(
                "/api/image-archives/projects/" + pid + "/versions?viewType=FRONT")
                .header(ROLE_HEADER, "ARCHIVIST"))
                .andExpect(status().isOk()).andReturn());
        assertEquals(2, versions.size());
        assertEquals(2, versions.get(0).get("versionNo").asInt(), "版本号倒序");

        byte[] latest = jpeg(200, 151, Color.BLUE);
        MvcResult dl = mockMvc.perform(get(
                "/api/image-archives/projects/" + pid + "/views/FRONT/file")
                .header(ROLE_HEADER, "ARCHIVIST"))
                .andExpect(status().isOk()).andReturn();
        assertArrayEquals(latest, dl.getResponse().getContentAsByteArray());
        assertTrue(dl.getResponse().getHeader("Content-Disposition").contains("f2.jpg"));
    }

    // ----------------------------------------------------------------
    // 4. 同阶段同视角：不同文件驳回（需新阶段），相同字节按去重
    // ----------------------------------------------------------------
    @Test
    void same_stage_different_file_rejected() throws Exception {
        long pid = createRepairProject("IMG-004", "IMG-P-004");
        byte[] first = jpeg(200, 150, Color.BLACK);
        assertEquals(1, uploadOne(pid, 1, "BACK", "b1.jpg", first, "p", SHOT_AT)
                .get("versionNo").asInt());

        JsonNode different = uploadOne(pid, 1, "BACK", "b2.jpg",
                jpeg(200, 150, Color.WHITE), "p", SHOT_AT);
        assertEquals("REJECTED", different.get("status").asText());
        assertTrue(different.get("reason").asText().contains("新修复阶段形成新版本"),
                different.get("reason").asText());

        JsonNode same = uploadOne(pid, 1, "BACK", "b1-copy.jpg", first, "p", SHOT_AT);
        assertEquals("DEDUPED", same.get("status").asText());
    }

    // ----------------------------------------------------------------
    // 5. 逐张失败原因 + 部分成功：合法项落库，非法项带具体原因
    // ----------------------------------------------------------------
    @Test
    void per_item_failures_with_partial_success() throws Exception {
        long pid = createRepairProject("IMG-005", "IMG-P-005");

        MockMultipartHttpServletRequestBuilder req = batchRequest(pid, 1, null, SHOT_AT);
        // 合法：FRONT（批次级拍摄时间 + 逐项摄影师）
        req.file(jpgPart("ok.jpg", jpeg(200, 150, Color.RED))).param("viewTypes", "FRONT");
        // 分辨率不足：1x1
        req.file(jpgPart("tiny.jpg", jpeg(1, 1, Color.RED))).param("viewTypes", "BACK");
        // 视角非法
        req.file(jpgPart("side.jpg", jpeg(200, 150, Color.RED))).param("viewTypes", "SIDE");
        req.param("photographers", "逐项摄影师");
        req.param("photographers", "");
        req.param("photographers", "");

        JsonNode data = dataOf(performUpload(req, UPLOADER));
        assertEquals(1, data.get("archivedCount").asInt());
        assertEquals(2, data.get("rejectedCount").asInt());

        JsonNode tiny = data.get("items").get(1);
        assertEquals("REJECTED", tiny.get("status").asText());
        assertTrue(tiny.get("reason").asText().contains("分辨率不足"), tiny.get("reason").asText());
        assertTrue(tiny.get("reason").asText().contains("1x1"), tiny.get("reason").asText());

        JsonNode badView = data.get("items").get(2);
        assertEquals("REJECTED", badView.get("status").asText());
        assertTrue(badView.get("reason").asText().contains("视角非法"));

        // 只有 FRONT 一条归档
        JsonNode versions = dataOf(mockMvc.perform(get(
                "/api/image-archives/projects/" + pid + "/versions"))
                .andExpect(status().isOk()).andReturn());
        assertEquals(1, versions.size());
        assertEquals("FRONT", versions.get(0).get("viewType").asText());
    }

    // ----------------------------------------------------------------
    // 6. 同一批次内视角重复：首张 ARCHIVED，其余驳回
    // ----------------------------------------------------------------
    @Test
    void duplicate_view_within_batch_rejected() throws Exception {
        long pid = createRepairProject("IMG-006", "IMG-P-006");
        MockMultipartHttpServletRequestBuilder req = batchRequest(pid, 1, "p", SHOT_AT);
        req.file(jpgPart("d1.jpg", jpeg(200, 150, new Color(7)))).param("viewTypes", "DETAIL");
        req.file(jpgPart("d2.jpg", jpeg(200, 150, new Color(9)))).param("viewTypes", "DETAIL");

        JsonNode data = dataOf(performUpload(req, UPLOADER));
        assertEquals("ARCHIVED", data.get("items").get(0).get("status").asText());
        assertEquals("REJECTED", data.get("items").get(1).get("status").asText());
        assertTrue(data.get("items").get(1).get("reason").asText().contains("视角重复"));
    }

    // ----------------------------------------------------------------
    // 7. 摄影者 / 拍摄时间兜底链：成功、缺失、未来时刻
    // ----------------------------------------------------------------
    @Test
    void photographer_and_shot_at_fallback_chain() throws Exception {
        long pid = createRepairProject("IMG-007", "IMG-P-007");

        // 批次级兜底成功
        JsonNode ok = uploadOne(pid, 1, "FRONT", "a.jpg",
                jpeg(200, 150, Color.RED), "批次摄影师", SHOT_AT);
        assertEquals("ARCHIVED", ok.get("status").asText());

        addStage(pid, 2, "阶段二");
        // 摄影者全缺
        JsonNode noPhotographer = uploadOne(pid, 2, "BACK", "b.jpg",
                jpeg(200, 150, Color.RED), null, SHOT_AT);
        assertEquals("REJECTED", noPhotographer.get("status").asText());
        assertTrue(noPhotographer.get("reason").asText().contains("摄影者缺失"));

        addStage(pid, 3, "阶段三");
        // 拍摄时间全缺
        JsonNode noShotAt = uploadOne(pid, 3, "BOTTOM", "c.jpg",
                jpeg(200, 150, Color.RED), "p", null);
        assertEquals("REJECTED", noShotAt.get("status").asText());
        assertTrue(noShotAt.get("reason").asText().contains("拍摄时间缺失"));

        addStage(pid, 4, "阶段四");
        // 拍摄时间晚于上传时刻
        JsonNode future = uploadOne(pid, 4, "DETAIL", "d.jpg",
                jpeg(200, 150, Color.RED), "p", "2099-01-01T00:00:00");
        assertEquals("REJECTED", future.get("status").asText());
        assertTrue(future.get("reason").asText().contains("不能晚于上传时刻"));
    }

    // ----------------------------------------------------------------
    // 8. 批次级预检：项目作废、阶段不存在、VIEWER 无上传岗 → 整体失败
    // ----------------------------------------------------------------
    @Test
    void batch_prechecks_reject_whole_upload() throws Exception {
        long pid = createRepairProject("IMG-008", "IMG-P-008");

        // VIEWER（含缺省/未知岗位）不能上传
        expectApiFail(uploadResult(pid, 1, "FRONT", "a.jpg",
                jpeg(200, 150, Color.RED), "p", SHOT_AT, "VIEWER"), "无影像上传权限");
        expectApiFail(uploadResult(pid, 1, "FRONT", "a.jpg",
                jpeg(200, 150, Color.RED), "p", SHOT_AT, "WIZARD"), "无影像上传权限");

        // 不存在的修复阶段
        expectApiFail(uploadResult(pid, 9, "FRONT", "a.jpg",
                jpeg(200, 150, Color.RED), "p", SHOT_AT, UPLOADER), "修复阶段不存在");

        // 项目作废后整体不能上传（PLANNED->REPAIRING 可作废）
        MvcResult cancel = postJson("/api/repair-projects/" + pid + "/cancel",
                "{\"reason\":\"项目取消\"}");
        assertEquals(true, rootOf(cancel).get("success").asBoolean());
        expectApiFail(uploadResult(pid, 1, "FRONT", "a.jpg",
                jpeg(200, 150, Color.RED), "p", SHOT_AT, UPLOADER), "项目已作废");
    }

    // ----------------------------------------------------------------
    // 9. 作废不删原文件；下载失效；重复作废与岗位限制
    // ----------------------------------------------------------------
    @Test
    void void_batch_keeps_file_and_facts() throws Exception {
        long pid = createRepairProject("IMG-009", "IMG-P-009");
        byte[] bytes = jpeg(200, 150, Color.CYAN);
        JsonNode uploaded = uploadOne(pid, 1, "FRONT", "keep.jpg", bytes, "p", SHOT_AT);
        long batchId = findFirstBatchId(pid);
        String sha = uploaded.get("sha256").asText();

        // RESTORER 无权作废
        MvcResult deniedResult = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/image-archives/batches/" + batchId + "/void")
                        .contentType("application/json").content("{\"reason\":\"越权\"}")
                        .header(ROLE_HEADER, "RESTORER"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(false, rootOf(deniedResult).get("success").asBoolean());
        assertTrue(rootOf(deniedResult).get("message").asText().contains("作废权限"));

        // ARCHIVIST 作废成功
        MvcResult voidResult = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/image-archives/batches/" + batchId + "/void")
                        .contentType("application/json").content("{\"reason\":\"误拍作废\"}")
                        .header(ROLE_HEADER, "ARCHIVIST"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(true, rootOf(voidResult).get("success").asBoolean());

        // 原文件与 blob 行保留（按本张 sha 定位，不受其他用例数据影响）
        assertEquals(1, blobMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.evops.entity.ImageBlob>()
                        .eq("sha256", sha)), "作废不删除 blob 行");
        assertTrue(blobStorage.existsOnDisk(blobMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.evops.entity.ImageBlob>()
                        .eq("sha256", sha)).getStoragePath()), "作废不删除磁盘原文件");

        // 该视角已无有效版本，下载失败（写流前报错）
        MvcResult dl = mockMvc.perform(get(
                "/api/image-archives/projects/" + pid + "/views/FRONT/file"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(false, rootOf(dl).get("success").asBoolean());
        assertTrue(rootOf(dl).get("message").asText().contains("无有效影像版本"));

        // 版本历史仍能查到 VOIDED 事实
        JsonNode versions = dataOf(mockMvc.perform(get(
                "/api/image-archives/projects/" + pid + "/versions?viewType=FRONT"))
                .andExpect(status().isOk()).andReturn());
        assertEquals("VOIDED", versions.get(0).get("status").asText());

        // 重复作废报错
        MvcResult again = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/image-archives/batches/" + batchId + "/void")
                        .contentType("application/json").content("{\"reason\":\"再作废\"}")
                        .header(ROLE_HEADER, "CURATOR"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(false, rootOf(again).get("success").asBoolean());
        assertTrue(rootOf(again).get("message").asText().contains("不能重复作废"));
    }

    // ----------------------------------------------------------------
    // 10. 岗位脱敏：VIEWER 遮蔽摄影者/机型/指纹，时间仅日期；ARCHIVIST 全量
    // ----------------------------------------------------------------
    @Test
    void metadata_masked_by_role() throws Exception {
        long pid = createRepairProject("IMG-010", "IMG-P-010");
        uploadOne(pid, 1, "FRONT", "m.jpg", jpeg(200, 150, Color.MAGENTA), "摄影者丁", SHOT_AT);

        JsonNode archivist = dataOf(mockMvc.perform(get(
                "/api/image-archives/projects/" + pid + "/versions?viewType=FRONT")
                .header(ROLE_HEADER, "ARCHIVIST"))
                .andExpect(status().isOk()).andReturn()).get(0);
        assertEquals("摄影者丁", archivist.get("photographer").asText());
        assertEquals("2026-09-01T10:00:00", archivist.get("shotAt").asText());
        assertNotNull(archivist.get("sha256"));
        assertNotNull(archivist.get("widthPx"));

        JsonNode viewer = dataOf(mockMvc.perform(get(
                "/api/image-archives/projects/" + pid + "/versions?viewType=FRONT")
                .header(ROLE_HEADER, "VIEWER"))
                .andExpect(status().isOk()).andReturn()).get(0);
        assertTrue(viewer.get("photographer") == null || viewer.get("photographer").isNull(),
                "VIEWER 遮蔽摄影者");
        assertTrue(viewer.get("cameraModel") == null || viewer.get("cameraModel").isNull(),
                "VIEWER 遮蔽机型");
        assertTrue(viewer.get("sha256") == null || viewer.get("sha256").isNull(),
                "VIEWER 遮蔽内容指纹");
        assertEquals("2026-09-01T00:00:00", viewer.get("shotAt").asText(), "VIEWER 时间仅到日期");
        assertEquals(200, viewer.get("widthPx").asInt(), "技术规格仍可见");

        // 下载：VIEWER 无敏感响应头但字节可下载；ARCHIVIST 有摄影者头
        MvcResult viewerDl = mockMvc.perform(get(
                "/api/image-archives/projects/" + pid + "/views/FRONT/file")
                .header(ROLE_HEADER, "VIEWER"))
                .andExpect(status().isOk()).andReturn();
        assertNull(viewerDl.getResponse().getHeader("X-Image-Photographer"));
        assertNull(viewerDl.getResponse().getHeader("X-Image-Sha256"));

        MvcResult archivistDl = mockMvc.perform(get(
                "/api/image-archives/projects/" + pid + "/views/FRONT/file")
                .header(ROLE_HEADER, "ARCHIVIST"))
                .andExpect(status().isOk()).andReturn();
        assertNotNull(archivistDl.getResponse().getHeader("X-Image-Photographer"));
        assertEquals("FRONT", archivistDl.getResponse().getHeader("X-Image-View-Type"));
    }

    // ----------------------------------------------------------------
    // 辅助
    // ----------------------------------------------------------------

    private JsonNode uploadOne(long pid, int stageNo, String view, String fileName,
                               byte[] bytes, String photographer, String shotAt) throws Exception {
        return dataOf(uploadResult(pid, stageNo, view, fileName, bytes, photographer, shotAt, UPLOADER))
                .get("items").get(0);
    }

    private MvcResult uploadResult(long pid, int stageNo, String view, String fileName,
                                   byte[] bytes, String photographer, String shotAt,
                                   String role) throws Exception {
        MockMultipartHttpServletRequestBuilder req = batchRequest(pid, stageNo, photographer, shotAt);
        req.file(new MockMultipartFile("files", fileName, "image/jpeg", bytes));
        req.param("viewTypes", view);
        return performUpload(req, role);
    }

    private MvcResult performUpload(MockMultipartHttpServletRequestBuilder req, String role) throws Exception {
        return mockMvc.perform(req.header(ROLE_HEADER, role))
                .andExpect(status().isOk()).andReturn();
    }

    private long findFirstBatchId(long pid) throws Exception {
        JsonNode versions = dataOf(mockMvc.perform(get(
                "/api/image-archives/projects/" + pid + "/versions"))
                .andExpect(status().isOk()).andReturn());
        return versions.get(0).get("batchId").asLong();
    }

    private void expectApiFail(MvcResult result, String messageContains) throws Exception {
        JsonNode root = rootOf(result);
        assertEquals(false, root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(messageContains),
                "应包含「" + messageContains + "」，实际：" + root.get("message").asText());
    }
}
