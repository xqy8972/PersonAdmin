package com.project.person.admin.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 投资买卖交易记录：买入/加仓为 BUY，卖出/减仓为 SELL。
 * K 线图上买卖标记与「买入后历史盈亏」实时计算的数据源，持久化在 H2。
 */
public class InvestmentTrade {

    public static final String TYPE_BUY = "BUY";
    public static final String TYPE_SELL = "SELL";

    private Long id;
    private Long userId;
    private Long investmentId;
    /** BUY=买入 / SELL=卖出 */
    private String tradeType;
    private LocalDate tradeDate;
    /** 股数/份额（金额型投资为 null） */
    private Integer shares;
    /** 成交单价（股票为对应币种，人民币标的即人民币） */
    private BigDecimal price;
    /** 成交金额（人民币） */
    private BigDecimal amount;
    /** 卖出资金去向资产（仅 SELL） */
    private Long targetAssetId;
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

    public Long getInvestmentId() {
        return investmentId;
    }

    public void setInvestmentId(Long investmentId) {
        this.investmentId = investmentId;
    }

    public String getTradeType() {
        return tradeType;
    }

    public void setTradeType(String tradeType) {
        this.tradeType = tradeType;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }

    public Integer getShares() {
        return shares;
    }

    public void setShares(Integer shares) {
        this.shares = shares;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Long getTargetAssetId() {
        return targetAssetId;
    }

    public void setTargetAssetId(Long targetAssetId) {
        this.targetAssetId = targetAssetId;
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
