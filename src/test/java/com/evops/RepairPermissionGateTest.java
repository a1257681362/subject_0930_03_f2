package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修复权限闸验收：收紧后影像上传停止推进，但已确认版本/原文件/下载保留；
 * 放宽后恢复；仅 CURATOR 可调整；收紧/放宽事件只追加。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:image-perm;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairPermissionGateTest extends ImageApiTestSupport {

    private MvcResult permission(String verb, String role, String body) throws Exception {
        String uri = "/api/repair-permission" + (verb == null ? "" : "/" + verb);
        if (verb == null) {
            return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
        }
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(uri).contentType("application/json").content(body)
                        .header(ROLE_HEADER, role == null ? "" : role))
                .andExpect(status().isOk()).andReturn();
    }

    @Test
    void tighten_stops_upload_but_keeps_confirmed_facts() throws Exception {
        long pid = createRepairProject("PERM-001", "PERM-P-001");

        // 初始状态惰性创建为开放
        JsonNode initial = dataOf(permission(null, null, null));
        assertTrue(initial.get("imageUploadOpen").asBoolean());

        // 收紧前先归档一张 FRONT（已确认事实）
        JsonNode archived = uploadOne(pid, 1, "FRONT", "before.jpg",
                jpeg(200, 150, Color.ORANGE), "p", SHOT_AT);
        assertEquals("ARCHIVED", archived.get("status").asText());

        // 非 CURATOR 不能收紧
        JsonNode denied = rootOf(permission("tighten", "ARCHIVIST", "{\"reason\":\"尝试收紧\"}"));
        assertFalse(denied.get("success").asBoolean());
        assertTrue(denied.get("message").asText().contains("仅业务主管"));

        // CURATOR 收紧
        JsonNode tightened = rootOf(permission("tighten", "CURATOR",
                "{\"reason\":\"修复权限专项审计期间暂停新增影像\"}"));
        assertTrue(tightened.get("success").asBoolean());
        assertFalse(dataOf(permission(null, null, null)).get("imageUploadOpen").asBoolean());

        // 收紧后上传整体停止推进（RESTORER/ARCHIVIST 都不行），原因带回调用方
        JsonNode blocked = rootOf(uploadResultRaw(pid, 1, "BACK", "x.jpg",
                jpeg(200, 150, Color.PINK), "p", SHOT_AT, "RESTORER"));
        assertFalse(blocked.get("success").asBoolean());
        assertTrue(blocked.get("message").asText().contains("权限已收紧"));
        assertTrue(blocked.get("message").asText().contains("专项审计"));

        // 已确认的旧版本：版本列表仍可查、原始文件仍可下载
        JsonNode versions = dataOf(mockMvc.perform(get(
                "/api/image-archives/projects/" + pid + "/versions?viewType=FRONT"))
                .andExpect(status().isOk()).andReturn());
        assertEquals(1, versions.size());
        MvcResult download = mockMvc.perform(get(
                "/api/image-archives/projects/" + pid + "/views/FRONT/file"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(download.getResponse().getContentType().startsWith("image/jpeg"));

        // 收紧状态下重复收紧报错；非 CURATOR 不能放宽
        assertTrue(rootOf(permission("tighten", "CURATOR", "{\"reason\":\"再收紧\"}"))
                .get("message").asText().contains("已处于收紧状态"));
        assertFalse(rootOf(permission("relax", "RESTORER", "{\"reason\":\"尝试放宽\"}"))
                .get("success").asBoolean());

        // CURATOR 放宽，上传恢复
        JsonNode relaxed = rootOf(permission("relax", "CURATOR",
                "{\"reason\":\"专项审计结束，恢复影像上传\"}"));
        assertTrue(relaxed.get("success").asBoolean());
        assertTrue(dataOf(permission(null, null, null)).get("imageUploadOpen").asBoolean());

        JsonNode after = dataOf(uploadResultRaw(pid, 1, "BACK", "after.jpg",
                jpeg(200, 151, Color.GRAY), "p", "2026-09-03T09:00:00")).get("items").get(0);
        assertEquals("ARCHIVED", after.get("status").asText());

        // 事件流水只追加：TIGHTEN 与 RELAX 均在
        JsonNode events = dataOf(permission(null, null, null)).get("events");
        boolean hasTighten = false;
        boolean hasRelax = false;
        for (JsonNode e : events) {
            if ("TIGHTEN".equals(e.get("eventType").asText())
                    && e.get("reason").asText().contains("专项审计")) {
                hasTighten = true;
            }
            if ("RELAX".equals(e.get("eventType").asText())) {
                hasRelax = true;
            }
        }
        assertTrue(hasTighten && hasRelax, "收紧/放宽事件都应只追加保留");
        assertNotNull(events);
    }

    private JsonNode uploadOne(long pid, int stageNo, String view, String fileName,
                               byte[] bytes, String photographer, String shotAt) throws Exception {
        return dataOf(uploadResultRaw(pid, stageNo, view, fileName, bytes, photographer, shotAt, "ARCHIVIST"))
                .get("items").get(0);
    }

    private MvcResult uploadResultRaw(long pid, int stageNo, String view, String fileName,
                                      byte[] bytes, String photographer, String shotAt) throws Exception {
        return uploadResultRaw(pid, stageNo, view, fileName, bytes, photographer, shotAt, "ARCHIVIST");
    }

    private MvcResult uploadResultRaw(long pid, int stageNo, String view, String fileName,
                                      byte[] bytes, String photographer, String shotAt,
                                      String role) throws Exception {
        return mockMvc.perform(batchRequest(pid, stageNo, photographer, shotAt)
                        .file(jpgPart(fileName, bytes))
                        .param("viewTypes", view)
                        .header(ROLE_HEADER, role))
                .andExpect(status().isOk()).andReturn();
    }
}
