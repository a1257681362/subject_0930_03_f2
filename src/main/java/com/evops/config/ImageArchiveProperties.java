package com.evops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文物多角度影像归档参数（evops.image.*）。
 */
@Component
@ConfigurationProperties(prefix = "evops.image")
public class ImageArchiveProperties {

    /** 内容寻址仓库根目录 */
    private String storeDir = "./data/image-store";

    /** 允许归档的最小宽度（像素） */
    private int minWidth = 800;

    /** 允许归档的最小高度（像素） */
    private int minHeight = 600;

    /** 单个拍摄批次最多照片数 */
    private int maxBatchSize = 20;

    public String getStoreDir() { return storeDir; }
    public void setStoreDir(String storeDir) { this.storeDir = storeDir; }
    public int getMinWidth() { return minWidth; }
    public void setMinWidth(int minWidth) { this.minWidth = minWidth; }
    public int getMinHeight() { return minHeight; }
    public void setMinHeight(int minHeight) { this.minHeight = minHeight; }
    public int getMaxBatchSize() { return maxBatchSize; }
    public void setMaxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; }
}
