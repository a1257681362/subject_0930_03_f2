package com.evops.service.support;

import com.evops.config.ImageArchiveProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 内容寻址的本地影像仓库：物理路径为 {@code <storeDir>/ab/cd/<64位sha256>}。
 * 同字节文件永远只落一份；重复保存幂等。作废归档不删除此处文件。
 */
@Component
public class BlobStorage {

    private final Path root;

    public BlobStorage(ImageArchiveProperties properties) {
        this.root = Paths.get(properties.getStoreDir()).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("无法初始化影像仓库目录: " + root, e);
        }
    }

    /** 相对仓库根的存储路径（也是定位句柄）：ab/cd/sha */
    public String relativePath(String sha256) {
        return sha256.substring(0, 2) + "/" + sha256.substring(2, 4) + "/" + sha256;
    }

    /**
     * 原子落盘。目标已存在（重复或并发写入）视为成功，物理文件始终只有一份。
     * @return 相对路径
     */
    public String store(String sha256, byte[] bytes) {
        String relative = relativePath(sha256);
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法存储路径");
        }
        if (Files.exists(target)) {
            return relative;
        }
        Path dir = target.getParent();
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(".tmp-" + sha256 + "-" + UUID.randomUUID());
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target);
            } catch (IOException e) {
                // 并发下别的请求已移入同内容目标：幂等成功
                if (Files.exists(target)) {
                    Files.deleteIfExists(tmp);
                    return relative;
                }
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("影像落盘失败: " + relative, e);
        }
        return relative;
    }

    /** 按相对路径解析为绝对路径供流式读取 */
    public Path resolve(String relativePath) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法存储路径");
        }
        return target;
    }

    public boolean existsOnDisk(String relativePath) {
        return Files.exists(resolve(relativePath));
    }
}
