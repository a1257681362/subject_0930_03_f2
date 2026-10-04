package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.mapper.ImageBlobMapper;
import com.evops.service.support.BlobStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 影像归档测试共用支撑：内存造 JPEG、走 HTTP 造修复项目数据。
 * Spring 测试上下文注解由各子类声明（各自独立内存库）。
 */
abstract class ImageApiTestSupport {

    static final String ROLE_HEADER = "X-Staff-Role";
    static final String SHOT_AT = "2026-09-01T10:00:00";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    ImageBlobMapper blobMapper;
    @Autowired
    BlobStorage blobStorage;

    // ---- 造图 ----

    static byte[] jpeg(int w, int h, Color color) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        g.drawLine(0, 0, Math.max(1, w - 1), Math.max(1, h - 1));
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    static MockMultipartFile jpgPart(String name, byte[] bytes) {
        return new MockMultipartFile("files", name, "image/jpeg", bytes);
    }

    /** 造一个已开工、已登记 1 个修复阶段的项目，返回项目 id */
    long createRepairProject(String relicNo, String projectNo) throws Exception {
        postJson("/api/relics", "{"
                + "\"relicNo\":\"" + relicNo + "\",\"name\":\"影像测试文物" + relicNo + "\""
                + ",\"material\":\"陶\",\"era\":\"汉\",\"source\":\"发掘\""
                + ",\"preservationGrade\":\"TWO\"}");
        postJson("/api/relics/" + findRelicId(relicNo) + "/intake", "{"
                + "\"intakeNo\":\"IU-" + relicNo + "\",\"location\":\"三号库·C-01\""
                + ",\"receiver\":\"库管员\""
                + ",\"photos\":[{\"photoUrl\":\"photos/" + relicNo + "/a.jpg\",\"viewType\":\"正面\"}]}");
        long projectId = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"" + projectNo + "\",\"relicId\":" + findRelicId(relicNo)
                + ",\"client\":\"藏品处\",\"plan\":\"影像测试修复方案\"}")).get("id").asLong();
        postEmpty("/api/repair-projects/" + projectId + "/start");
        addStage(projectId, 1, "清洗");
        return projectId;
    }

    long findRelicId(String relicNo) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/relics?keyword=" + relicNo)).andExpect(status().isOk()).andReturn();
        JsonNode list = dataOf(result);
        return list.get(0).get("id").asLong();
    }

    void addStage(long projectId, int stageNo, String name) throws Exception {
        postJson("/api/repair-projects/" + projectId + "/stages", "{"
                + "\"stageName\":\"" + name + stageNo + "\",\"stageResult\":\"阶段" + stageNo + "结果\""
                + ",\"operator\":\"修复师甲\"}");
    }

    MockMultipartHttpServletRequestBuilder batchRequest(long projectId, int stageNo,
                                                        String photographer, String shotAt) {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/image-archives/batches");
        builder.param("projectId", String.valueOf(projectId));
        builder.param("stageNo", String.valueOf(stageNo));
        if (photographer != null) {
            builder.param("photographer", photographer);
        }
        if (shotAt != null) {
            builder.param("shotAt", shotAt);
        }
        return builder;
    }

    // ---- HTTP 基础辅助（风格同 MuseumLedgerRuleTest） ----

    MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    MvcResult postEmpty(String uri) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
    }

    JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    JsonNode rootOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
