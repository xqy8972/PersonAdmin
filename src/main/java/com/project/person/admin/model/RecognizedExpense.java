package com.project.person.admin.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * AI 识别出的待确认支出（不落库）：用户检查/修改后走正常创建流程才真正扣减。
 */
public class RecognizedExpense {

    /** 识别出的金额（元） */
    private BigDecimal amount;

    /** 识别出的支出日期 */
    private LocalDate recordDate;

    /** 识别出的分类 */
    private String category;

    /** 支付渠道原文（图片中提取，如“招商银行”“支付宝”） */
    private String assetHint;

    /** 匹配到的用户资产 id（现金/银行卡），未匹配为 null，由用户手动选择 */
    private Long assetId;

    /** 匹配到的用户资产名称（展示用） */
    private String assetName;

    /** 商户名/说明 */
    private String remark;

    /** 识别失败原因；成功为 null */
    private String error;

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDate getRecordDate() {
        return recordDate;
    }

    public void setRecordDate(LocalDate recordDate) {
        this.recordDate = recordDate;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getAssetHint() {
        return assetHint;
    }

    public void setAssetHint(String assetHint) {
        this.assetHint = assetHint;
    }

    public Long getAssetId() {
        return assetId;
    }

    public void setAssetId(Long assetId) {
        this.assetId = assetId;
    }

    public String getAssetName() {
        return assetName;
    }

    public void setAssetName(String assetName) {
        this.assetName = assetName;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
