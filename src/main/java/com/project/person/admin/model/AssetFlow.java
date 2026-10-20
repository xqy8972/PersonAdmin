package com.project.person.admin.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 资产变动流水：收入/支出/投资买卖/资产卖出等引起的资产余额变化逐笔记账，
 * 与投资交易（investment_trade）同级的持久化台账。金额为带符号人民币值（+流入/-流出）。
 */
public class AssetFlow {

    private Long id;
    private Long userId;
    private Long assetId;
    /** 变动类型：收入/支出/投资买入/投资卖出/投资新增/投资编辑/投资删除/资产卖出/资产调整 */
    private String flowType;
    /** 带符号金额（人民币）：正=资产增加，负=资产减少 */
    private BigDecimal amount;
    /** 变动发生日期（跟随业务单据日期，如交易日期/收支日期） */
    private LocalDate flowDate;
    /** 来源单据类型：income/expense/investment/investment_trade/asset */
    private String refType;
    /** 来源单据 id（编辑单据时先按 ref 删除旧流水再补记） */
    private Long refId;
    private String remark;
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getAssetId() {
        return assetId;
    }

    public void setAssetId(Long assetId) {
        this.assetId = assetId;
    }

    public String getFlowType() {
        return flowType;
    }

    public void setFlowType(String flowType) {
        this.flowType = flowType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDate getFlowDate() {
        return flowDate;
    }

    public void setFlowDate(LocalDate flowDate) {
        this.flowDate = flowDate;
    }

    public String getRefType() {
        return refType;
    }

    public void setRefType(String refType) {
        this.refType = refType;
    }

    public Long getRefId() {
        return refId;
    }

    public void setRefId(Long refId) {
        this.refId = refId;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
