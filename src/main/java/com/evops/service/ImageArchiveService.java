package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.config.ImageArchiveProperties;
import com.evops.constant.StaffRole;
import com.evops.constant.ViewType;
import com.evops.dto.image.ImageBatchDetail;
import com.evops.dto.image.ImageBatchItemResult;
import com.evops.dto.image.ImageBatchUploadForm;
import com.evops.dto.image.ImageBatchUploadResult;
import com.evops.dto.image.MaskedImageMeta;
import com.evops.entity.ImageArchive;
import com.evops.entity.ImageBatch;
import com.evops.entity.ImageBlob;
import com.evops.entity.RepairProject;
import com.evops.entity.RepairStage;
import com.evops.mapper.ImageArchiveMapper;
import com.evops.mapper.ImageBatchMapper;
import com.evops.mapper.ImageBlobMapper;
import com.evops.mapper.RepairStageMapper;
import com.evops.service.support.BlobStorage;
import com.evops.service.support.ImageMetadataMasker;
import com.evops.service.support.ImageProbeService;
import com.evops.service.support.ProbedImage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static com.evops.constant.MuseumConstants.*;

/**
 * 文物多角度影像归档核心：
 * 1) 正面/背面/底部/细部按拍摄批次挂到「修复项目 + 修复阶段」；
 * 2) 上传校验分辨率（实测）、拍摄时间、摄影者（EXIF 优先，表单兜底）；
 * 3) 同一文件按 SHA-256 物理只存一份，(项目,视角,文件) 引用也只一条，重复上传回指既有版本；
 * 4) (项目,视角) 的版本号跨修复阶段递增，作废不回收；
 * 5) 作废只置 VOIDED，原文件与全部记录保留；
 * 6) 批量上传逐项给结果，合法项提交、非法项给具体原因；
 * 7) 修复权限收紧时本服务在预检阶段即停止推进。
 */
@Service
public class ImageArchiveService {

    private static final DateTimeFormatter BATCH_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final ImageArchiveProperties properties;
    private final ImageProbeService probeService;
    private final BlobStorage blobStorage;
    private final ImageMetadataMasker masker;
    private final ImageBlobMapper blobMapper;
    private final ImageBatchMapper batchMapper;
    private final ImageArchiveMapper archiveMapper;
    private final RepairStageMapper stageMapper;
    private final RepairService repairService;
    private final RepairPermissionService permissionService;
    private final LedgerService ledgerService;

    public ImageArchiveService(ImageArchiveProperties properties,
                               ImageProbeService probeService,
                               BlobStorage blobStorage,
                               ImageMetadataMasker masker,
                               ImageBlobMapper blobMapper,
                               ImageBatchMapper batchMapper,
                               ImageArchiveMapper archiveMapper,
                               RepairStageMapper stageMapper,
                               RepairService repairService,
                               RepairPermissionService permissionService,
                               LedgerService ledgerService) {
        this.properties = properties;
        this.probeService = probeService;
        this.blobStorage = blobStorage;
        this.masker = masker;
        this.blobMapper = blobMapper;
        this.batchMapper = batchMapper;
        this.archiveMapper = archiveMapper;
        this.stageMapper = stageMapper;
        this.repairService = repairService;
        this.permissionService = permissionService;
        this.ledgerService = ledgerService;
    }

    // ------------------------------------------------------------------
    // 批量上传
    // ------------------------------------------------------------------

    @Transactional
    public ImageBatchUploadResult uploadBatch(ImageBatchUploadForm form, StaffRole role) {
        // ---- A. 批次级预检：任一不满足整体失败，不落任何数据 ----
        if (role == null || !role.canUpload()) {
            throw new IllegalArgumentException("当前岗位无影像上传权限（仅修复师 RESTORER / 档案员 ARCHIVIST）");
        }
        // 权限收紧闸：收紧时影像上传停止推进，已归档版本与文件不受影响
        permissionService.requireUploadOpen();

        MultipartFile[] files = form.getFiles();
        int n = files == null ? 0 : files.length;
        if (n == 0) {
            throw new IllegalArgumentException("批次至少上传一张照片");
        }
        if (n > properties.getMaxBatchSize()) {
            throw new IllegalArgumentException("单批次最多 " + properties.getMaxBatchSize() + " 张照片，实际 " + n + " 张");
        }
        List<String> viewTypes = form.getViewTypes() == null ? Collections.emptyList() : form.getViewTypes();
        if (viewTypes.size() != n) {
            throw new IllegalArgumentException("viewTypes 数量必须与照片数量一致：" + viewTypes.size() + " != " + n);
        }
        if (form.getShotTimes() != null && form.getShotTimes().size() != n) {
            throw new IllegalArgumentException("shotTimes 数量必须与照片数量一致");
        }
        if (form.getPhotographers() != null && form.getPhotographers().size() != n) {
            throw new IllegalArgumentException("photographers 数量必须与照片数量一致");
        }

        RepairProject project = repairService.requireProject(form.getProjectId());
        if (PROJECT_CANCELLED.equals(project.getStatus())) {
            throw new IllegalArgumentException("修复项目已作废，不能归档影像: " + project.getProjectNo());
        }
        Long stageCount = stageMapper.selectCount(new LambdaQueryWrapper<RepairStage>()
                .eq(RepairStage::getProjectId, project.getId())
                .eq(RepairStage::getStageNo, form.getStageNo()));
        if (stageCount == null || stageCount == 0) {
            throw new IllegalArgumentException("修复阶段不存在: projectId=" + project.getId()
                    + ", stageNo=" + form.getStageNo());
        }
        LocalDateTime batchShotAt = parseShotAt(form.getShotAt(), "批次拍摄时间");
        String batchPhotographer = trimToNull(form.getPhotographer());
        LocalDateTime now = LocalDateTime.now();

        String batchNo = newBatchNo(now);
        ImageBatchUploadResult result = new ImageBatchUploadResult();
        result.setBatchNo(batchNo);
        result.setProjectId(project.getId());
        result.setStageNo(form.getStageNo());
        List<ImageBatchItemResult> items = new ArrayList<>(n);
        List<PendingItem> pending = new ArrayList<>();
        Set<String> usedViews = new HashSet<>();

        // ---- B/C. 逐文件探测与业务校验 ----
        for (int i = 0; i < n; i++) {
            MultipartFile file = files[i];
            String fileName = safeFileName(file.getOriginalFilename());
            String viewCode = viewTypes.get(i);
            ViewType view = ViewType.fromCode(viewCode);

            String itemPhotographer = form.getPhotographers() == null
                    ? null : trimToNull(form.getPhotographers().get(i));
            LocalDateTime itemShotAt = form.getShotTimes() == null
                    ? null : parseShotAt(form.getShotTimes().get(i), "第" + (i + 1) + "张拍摄时间");

            // 视角非法
            if (view == null) {
                items.add(reject(i, fileName, viewCode, "视角非法，仅支持 FRONT/BACK/BOTTOM/DETAIL"));
                continue;
            }
            // 同批次内视角重复：本批次每张只承担一个视角的一个版本
            if (!usedViews.add(view.name())) {
                items.add(reject(i, fileName, view.name(),
                        "同一批次内视角重复: " + view.getLabel() + "（一个批次同一视角只能一张）"));
                continue;
            }

            byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (IOException e) {
                items.add(reject(i, fileName, view.name(), "文件读取失败: " + e.getMessage()));
                continue;
            }
            ProbedImage probed = probeService.probe(bytes, file.getContentType());
            if (!probed.isReadable()) {
                items.add(reject(i, fileName, view.name(), "无法识别的图片格式（仅支持 JPEG/PNG 影像）"));
                continue;
            }
            if (probed.getWidth() < properties.getMinWidth() || probed.getHeight() < properties.getMinHeight()) {
                items.add(reject(i, fileName, view.name(),
                        "分辨率不足：实际 " + probed.getWidth() + "x" + probed.getHeight()
                                + "，最小要求 " + properties.getMinWidth() + "x" + properties.getMinHeight()));
                continue;
            }

            // 摄影者：EXIF > 逐项表单 > 批次表单
            String photographer = firstNonBlank(probed.getExifPhotographer(), itemPhotographer, batchPhotographer);
            if (photographer == null) {
                items.add(reject(i, fileName, view.name(), "摄影者缺失（EXIF 与表单均未提供）"));
                continue;
            }
            // 拍摄时间：EXIF > 逐项表单 > 批次表单
            LocalDateTime shotAt = probed.getExifShotAt() != null ? probed.getExifShotAt()
                    : (itemShotAt != null ? itemShotAt : batchShotAt);
            if (shotAt == null) {
                items.add(reject(i, fileName, view.name(), "拍摄时间缺失（EXIF 与表单均未提供）"));
                continue;
            }
            if (shotAt.isAfter(now)) {
                items.add(reject(i, fileName, view.name(), "拍摄时间不能晚于上传时刻: " + shotAt));
                continue;
            }

            // 去重：物理 blob 已存在且 (项目,视角,blob) 引用已存在
            ImageBlob existingBlob = findBlob(probed.getSha256());
            if (existingBlob != null) {
                ImageArchive prior = archiveMapper.selectOne(new LambdaQueryWrapper<ImageArchive>()
                        .eq(ImageArchive::getProjectId, project.getId())
                        .eq(ImageArchive::getViewType, view.name())
                        .eq(ImageArchive::getBlobId, existingBlob.getId()));
                if (prior != null) {
                    if (IMAGE_VOIDED.equals(prior.getStatus())) {
                        items.add(reject(i, fileName, view.name(),
                                "该影像已在版本 v" + prior.getVersionNo() + " 归档且已作废，不能按同一文件重新归档"));
                    } else {
                        ImageBatchItemResult dedup = ImageBatchItemResult.of(i, fileName, view.name(),
                                ITEM_DEDUPED, prior.getVersionNo(), existingBlob.getSha256(),
                                "内容已归档，只保留一份引用，回指版本号 v" + prior.getVersionNo());
                        items.add(dedup);
                    }
                    continue;
                }
            }

            // 跨阶段版本序：不允许在更早的阶段补拍（该视角已归档到更后阶段）
            Long laterCount = archiveMapper.selectCount(new LambdaQueryWrapper<ImageArchive>()
                    .eq(ImageArchive::getProjectId, project.getId())
                    .eq(ImageArchive::getViewType, view.name())
                    .gt(ImageArchive::getStageNo, form.getStageNo()));
            if (laterCount != null && laterCount > 0) {
                items.add(reject(i, fileName, view.name(),
                        "视角" + view.getLabel() + "已归档到更后的修复阶段，不能在阶段 "
                                + form.getStageNo() + " 补拍"));
                continue;
            }
            // 同阶段同视角已有 ACTIVE 版本：不同文件必须到新修复阶段形成新版本
            Long sameStageActive = archiveMapper.selectCount(new LambdaQueryWrapper<ImageArchive>()
                    .eq(ImageArchive::getProjectId, project.getId())
                    .eq(ImageArchive::getViewType, view.name())
                    .eq(ImageArchive::getStageNo, form.getStageNo())
                    .eq(ImageArchive::getStatus, IMAGE_ACTIVE));
            if (sameStageActive != null && sameStageActive > 0) {
                items.add(reject(i, fileName, view.name(),
                        "视角" + view.getLabel() + "在阶段 " + form.getStageNo()
                                + " 已归档，不同文件需在新修复阶段形成新版本"));
                continue;
            }

            pending.add(new PendingItem(i, fileName, view, photographer, shotAt, probed, existingBlob));
            // 占位，阶段 D 填版本号
            items.add(null);
        }

        // ---- D. 落盘落库：只处理通过校验的项 ----
        ImageBatch batch = new ImageBatch();
        batch.setBatchNo(batchNo);
        batch.setRelicId(project.getRelicId());
        batch.setProjectId(project.getId());
        batch.setStageNo(form.getStageNo());
        batch.setPhotographer(batchPhotographer);
        batch.setShotAt(batchShotAt);
        batch.setUploadRole(role.name());
        batch.setStatus(IMAGE_ACTIVE);
        batch.setItemCount(0);
        batch.setDedupCount(0);
        batch.setRejectCount(0);
        batchMapper.insert(batch);
        result.setBatchId(batch.getId());

        int archived = 0;
        Map<String, Integer> versionByView = new LinkedHashMap<>();
        for (PendingItem p : pending) {
            int versionNo = nextVersionNo(project.getId(), p.view.name());
            ImageBlob blob = p.existingBlob != null ? p.existingBlob : materializeBlob(p.probed);

            ImageArchive archive = new ImageArchive();
            archive.setBatchId(batch.getId());
            archive.setRelicId(project.getRelicId());
            archive.setProjectId(project.getId());
            archive.setStageNo(form.getStageNo());
            archive.setViewType(p.view.name());
            archive.setVersionNo(versionNo);
            archive.setBlobId(blob.getId());
            archive.setFileName(p.fileName);
            archive.setPhotographer(p.photographer);
            archive.setShotAt(p.shotAt);
            archive.setStatus(IMAGE_ACTIVE);
            archiveMapper.insert(archive);
            archived++;
            versionByView.put(p.view.getLabel(), versionNo);

            items.set(p.index, ImageBatchItemResult.of(p.index, p.fileName, p.view.name(),
                    ITEM_ARCHIVED, versionNo, blob.getSha256(), "已归档为版本 v" + versionNo));
        }

        int deduped = 0;
        int rejected = 0;
        for (ImageBatchItemResult item : items) {
            if (ITEM_DEDUPED.equals(item.getStatus())) {
                deduped++;
            } else if (ITEM_REJECTED.equals(item.getStatus())) {
                rejected++;
            }
        }
        batch.setItemCount(archived);
        batch.setDedupCount(deduped);
        batch.setRejectCount(rejected);
        batchMapper.updateById(batch);

        result.setArchivedCount(archived);
        result.setDedupedCount(deduped);
        result.setRejectedCount(rejected);
        result.setItems(items);

        if (archived > 0) {
            ledgerService.append(project.getRelicId(), project.getId(), null,
                    ACTION_IMAGE_ARCHIVE, batchNo,
                    "影像批次归档 " + archived + " 张（" + joinVersions(versionByView)
                            + "），去重 " + deduped + " 张，驳回 " + rejected + " 张",
                    now);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 作废：只置状态，blob 原文件与磁盘文件保留
    // ------------------------------------------------------------------

    @Transactional
    public ImageBatch voidBatch(Long batchId, String reason, StaffRole role) {
        if (role == null || !role.canVoid()) {
            throw new IllegalArgumentException("当前岗位无影像作废权限（仅档案员 ARCHIVIST / 业务主管 CURATOR）");
        }
        ImageBatch batch = requireBatch(batchId);
        if (IMAGE_VOIDED.equals(batch.getStatus())) {
            throw new IllegalArgumentException("批次已作废，不能重复作废: " + batch.getBatchNo());
        }
        LocalDateTime now = LocalDateTime.now();
        batch.setStatus(IMAGE_VOIDED);
        batch.setVoidReason(reason);
        batch.setVoidedAt(now);
        batchMapper.updateById(batch);

        archiveMapper.update(null, new LambdaUpdateWrapper<ImageArchive>()
                .eq(ImageArchive::getBatchId, batchId)
                .eq(ImageArchive::getStatus, IMAGE_ACTIVE)
                .set(ImageArchive::getStatus, IMAGE_VOIDED)
                .set(ImageArchive::getVoidedAt, now));

        ledgerService.append(batch.getRelicId(), batch.getProjectId(), null,
                ACTION_IMAGE_VOID, batch.getBatchNo(),
                "影像批次作废，原因：" + reason + "（原文件保留，不删除）", now);
        return batch;
    }

    // ------------------------------------------------------------------
    // 查询（版本历史包含 VOIDED：归档事实永久保留）
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ImageBatchDetail getBatchDetail(Long batchId, StaffRole role) {
        ImageBatch batch = requireBatch(batchId);
        List<ImageArchive> archives = archiveMapper.selectList(new LambdaQueryWrapper<ImageArchive>()
                .eq(ImageArchive::getBatchId, batchId)
                .orderByAsc(ImageArchive::getId));
        Map<Long, ImageBlob> blobs = loadBlobs(archives);

        ImageBatchDetail detail = new ImageBatchDetail();
        detail.setId(batch.getId());
        detail.setBatchNo(batch.getBatchNo());
        detail.setRelicId(batch.getRelicId());
        detail.setProjectId(batch.getProjectId());
        detail.setStageNo(batch.getStageNo());
        detail.setStatus(batch.getStatus());
        detail.setVoidReason(batch.getVoidReason());
        detail.setVoidedAt(batch.getVoidedAt());
        detail.setUploadRole(batch.getUploadRole());
        detail.setItemCount(batch.getItemCount());
        detail.setDedupCount(batch.getDedupCount());
        detail.setRejectCount(batch.getRejectCount());
        detail.setCreateTime(batch.getCreateTime());
        for (ImageArchive archive : archives) {
            detail.getItems().add(masker.mask(archive, blobs.get(archive.getBlobId()),
                    batch.getBatchNo(), role));
        }
        return detail;
    }

    @Transactional(readOnly = true)
    public List<MaskedImageMeta> listVersions(Long projectId, String viewType, StaffRole role) {
        repairService.requireProject(projectId);
        LambdaQueryWrapper<ImageArchive> wrapper = new LambdaQueryWrapper<ImageArchive>()
                .eq(ImageArchive::getProjectId, projectId)
                .orderByAsc(ImageArchive::getViewType)
                .orderByDesc(ImageArchive::getVersionNo);
        if (viewType != null && !viewType.trim().isEmpty()) {
            ViewType view = ViewType.fromCode(viewType);
            if (view == null) {
                throw new IllegalArgumentException("视角非法，仅支持 FRONT/BACK/BOTTOM/DETAIL");
            }
            wrapper.eq(ImageArchive::getViewType, view.name());
        }
        List<ImageArchive> archives = archiveMapper.selectList(wrapper);
        Map<Long, ImageBlob> blobs = loadBlobs(archives);
        Map<Long, String> batchNoMap = loadBatchNos(archives);
        List<MaskedImageMeta> metas = new ArrayList<>();
        for (ImageArchive archive : archives) {
            metas.add(masker.mask(archive, blobs.get(archive.getBlobId()),
                    batchNoMap.get(archive.getBatchId()), role));
        }
        return metas;
    }

    /** 解析某视角当前有效（ACTIVE）的最高版本用于下载；不存在统一报错，不泄露是否曾作废 */
    @Transactional(readOnly = true)
    public DownloadTarget resolveForDownload(Long projectId, String viewType) {
        ViewType view = ViewType.fromCode(viewType);
        if (view == null) {
            throw new IllegalArgumentException("视角非法，仅支持 FRONT/BACK/BOTTOM/DETAIL");
        }
        repairService.requireProject(projectId);
        ImageArchive archive = archiveMapper.selectOne(new LambdaQueryWrapper<ImageArchive>()
                .eq(ImageArchive::getProjectId, projectId)
                .eq(ImageArchive::getViewType, view.name())
                .eq(ImageArchive::getStatus, IMAGE_ACTIVE)
                .orderByDesc(ImageArchive::getVersionNo)
                .last("LIMIT 1"));
        if (archive == null) {
            throw new IllegalArgumentException("视角" + view.getLabel() + "当前无有效影像版本");
        }
        ImageBlob blob = blobMapper.selectById(archive.getBlobId());
        if (blob == null || !blobStorage.existsOnDisk(blob.getStoragePath())) {
            throw new IllegalStateException("影像物理文件缺失: sha256=" + (blob == null ? "?" : blob.getSha256()));
        }
        return new DownloadTarget(archive, blob, blobStorage.resolve(blob.getStoragePath()));
    }

    public ImageBatch requireBatch(Long batchId) {
        ImageBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("影像批次不存在: id=" + batchId);
        }
        return batch;
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private int nextVersionNo(Long projectId, String view) {
        Integer max = archiveMapper.selectList(new LambdaQueryWrapper<ImageArchive>()
                        .eq(ImageArchive::getProjectId, projectId)
                        .eq(ImageArchive::getViewType, view)
                        .orderByDesc(ImageArchive::getVersionNo)
                        .last("LIMIT 1"))
                .stream().findFirst().map(ImageArchive::getVersionNo).orElse(0);
        return max + 1;
    }

    private ImageBlob findBlob(String sha256) {
        return blobMapper.selectOne(new LambdaQueryWrapper<ImageBlob>().eq(ImageBlob::getSha256, sha256));
    }

    /** 内容寻址落盘并建 blob 行；并发撞唯一索引时回查复用，物理文件始终只有一份 */
    private ImageBlob materializeBlob(ProbedImage probed) {
        String relative = blobStorage.store(probed.getSha256(), probed.getBytes());
        ImageBlob blob = new ImageBlob();
        blob.setSha256(probed.getSha256());
        blob.setStoragePath(relative);
        blob.setContentType(probed.getContentType());
        blob.setSizeBytes((long) probed.getBytes().length);
        blob.setWidthPx(probed.getWidth());
        blob.setHeightPx(probed.getHeight());
        blob.setCameraModel(probed.getCameraModel());
        try {
            blobMapper.insert(blob);
            return blob;
        } catch (DuplicateKeyException e) {
            ImageBlob existing = findBlob(probed.getSha256());
            if (existing != null) {
                return existing;
            }
            throw e;
        }
    }

    private Map<Long, ImageBlob> loadBlobs(List<ImageArchive> archives) {
        if (archives.isEmpty()) {
            return new HashMap<>();
        }
        Set<Long> ids = new HashSet<>();
        for (ImageArchive a : archives) {
            ids.add(a.getBlobId());
        }
        Map<Long, ImageBlob> map = new HashMap<>();
        for (ImageBlob blob : blobMapper.selectBatchIds(ids)) {
            map.put(blob.getId(), blob);
        }
        return map;
    }

    private Map<Long, String> loadBatchNos(List<ImageArchive> archives) {
        Set<Long> ids = new HashSet<>();
        for (ImageArchive a : archives) {
            ids.add(a.getBatchId());
        }
        Map<Long, String> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (ImageBatch batch : batchMapper.selectBatchIds(ids)) {
            map.put(batch.getId(), batch.getBatchNo());
        }
        return map;
    }

    private ImageBatchItemResult reject(int index, String fileName, String viewType, String reason) {
        return ImageBatchItemResult.of(index, fileName, viewType, ITEM_REJECTED, null, null, reason);
    }

    private LocalDateTime parseShotAt(String text, String fieldLabel) {
        String trimmed = trimToNull(text);
        if (trimmed == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(trimmed, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(fieldLabel + "格式应为 yyyy-MM-ddTHH:mm:ss，实际: " + text);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            String t = trimToNull(v);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safeFileName(String original) {
        String name = original == null ? "" : original.trim();
        if (name.isEmpty()) {
            return "photo";
        }
        name = name.replace("\\", "/");
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.isEmpty() ? "photo" : name;
    }

    private static String newBatchNo(LocalDateTime now) {
        return "IB" + BATCH_NO_FMT.format(now)
                + String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));
    }

    private static String joinVersions(Map<String, Integer> versionByView) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : versionByView.entrySet()) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(e.getKey()).append(" v").append(e.getValue());
        }
        return sb.toString();
    }

    /** 下载解析结果：归档版本 + 物理 blob + 磁盘路径 */
    public static class DownloadTarget {
        private final ImageArchive archive;
        private final ImageBlob blob;
        private final Path path;

        public DownloadTarget(ImageArchive archive, ImageBlob blob, Path path) {
            this.archive = archive;
            this.blob = blob;
            this.path = path;
        }

        public ImageArchive getArchive() { return archive; }
        public ImageBlob getBlob() { return blob; }
        public Path getPath() { return path; }
    }

    /** 通过校验、待落库的单张照片 */
    private static class PendingItem {
        private final int index;
        private final String fileName;
        private final ViewType view;
        private final String photographer;
        private final LocalDateTime shotAt;
        private final ProbedImage probed;
        private final ImageBlob existingBlob;

        PendingItem(int index, String fileName, ViewType view, String photographer,
                    LocalDateTime shotAt, ProbedImage probed, ImageBlob existingBlob) {
            this.index = index;
            this.fileName = fileName;
            this.view = view;
            this.photographer = photographer;
            this.shotAt = shotAt;
            this.probed = probed;
            this.existingBlob = existingBlob;
        }
    }
}
