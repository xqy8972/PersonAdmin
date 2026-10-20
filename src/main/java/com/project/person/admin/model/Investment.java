package com.project.person.admin.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class Investment {

    private Long id;
    private Long userId;
    private String name;
    private String type;

    /** 行情代码（选填）：股票带市场前缀 sh600519/sz000001，基金为 6 位数字 */
    private String code;
    private BigDecimal amount;
    private BigDecimal currentValue;
    private Integer shares;

    /** 出资资产 id（JSON 字段名 assetId，仅现金/银行卡类别） */
    @com.fasterxml.jackson.annotation.JsonProperty("assetId")
    private Long sourceAssetId;

    /** 当前股价（仅入参使用：股票新增/加仓时填写） */
    @com.fasterxml.jackson.annotation.JsonAlias("price")
    private BigDecimal price;

    public Long getSourceAssetId() {
        return sourceAssetId;
    }

    public void setSourceAssetId(Long sourceAssetId) {
        this.sourceAssetId = sourceAssetId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
    /** 外部渠道来源：THS=同花顺等，非空表示该投资由渠道同步维护（不支持编辑/卖出/删除） */
    private String sourceChannel;

    /** 最新一个交易日的盈亏（列表展示用，实时计算不落库）：盘中=(最新价−昨收)×持有数量，实时刷新 */
    private java.math.BigDecimal dayProfit;

    /** 盘中实时行情标记：行情更新时间为今日且当前处于该市场交易时段（「最新价」旁展示「盘中」角标） */
    private Boolean live;

    /**
     * 成本价（同花顺摊薄口径，列表展示用不落库）：(Σ买入价×量 − Σ卖出价×量，
     * 每笔按成交日汇率折人民币) ÷ 当前持有数量，再按当前汇率换算回标的币种
     * （港股=港币/股，A 股=元/股，基金=元/份）。无台账或已清仓返回 null。
     */
    private java.math.BigDecimal costPrice;

    /** 交易费率（%）：买入/卖出自动按 价×量×(1±费率/100) 计入手续费，null/0 表示不计 */
    private BigDecimal feeRate;

    /** 每笔固定手续费（元，人民币）：印花税取整/最低佣金/换汇尾差等，买入加成本、卖出扣回款，null/0 表示不计 */
    private BigDecimal feeFixed;

    private LocalDate buyDate;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public String getSourceChannel() {
        return sourceChannel;
    }

    public void setSourceChannel(String sourceChannel) {
        this.sourceChannel = sourceChannel;
    }

    public java.math.BigDecimal getDayProfit() {
        return dayProfit;
    }

    public void setDayProfit(java.math.BigDecimal dayProfit) {
        this.dayProfit = dayProfit;
    }

    public Boolean getLive() {
        return live;
    }

    public void setLive(Boolean live) {
        this.live = live;
    }

    public java.math.BigDecimal getCostPrice() {
        return costPrice;
    }

    public void setCostPrice(java.math.BigDecimal costPrice) {
        this.costPrice = costPrice;
    }

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getCurrentValue() {
        return currentValue;
    }

    public void setCurrentValue(BigDecimal currentValue) {
        this.currentValue = currentValue;
    }

    public Integer getShares() {
        return shares;
    }

    public void setShares(Integer shares) {
        this.shares = shares;
    }

    public LocalDate getBuyDate() {
        return buyDate;
    }

    public void setBuyDate(LocalDate buyDate) {
        this.buyDate = buyDate;
    }

    public BigDecimal getFeeRate() {
        return feeRate;
    }

    public void setFeeRate(BigDecimal feeRate) {
        this.feeRate = feeRate;
    }

    public BigDecimal getFeeFixed() {
        return feeFixed;
    }

    public void setFeeFixed(BigDecimal feeFixed) {
        this.feeFixed = feeFixed;
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

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
