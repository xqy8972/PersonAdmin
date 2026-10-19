package com.project.person.admin.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 投资卖出入参：股票/基金按份额+单价，其他类型按金额；assetId 为回款资产（现金/银行卡） */
public class InvestmentSell {

    /** 回款资产 id（JSON 字段名 assetId） */
    @com.fasterxml.jackson.annotation.JsonProperty("assetId")
    private Long assetId;

    /** 股票/基金：卖出份额 */
    private Integer shares;

    /** 股票/基金：卖出单价，留空按当前市价折算 */
    private BigDecimal price;

    /** 非股票/基金：卖出金额 */
    private BigDecimal amount;

    /** 卖出日期，默认今天；填过去日期用于补录历史卖出（交易记录与快照按该日期落） */
    private LocalDate tradeDate;

    /** 本笔固定手续费（元，人民币）：选填，传了覆盖投资上的默认值，照成交单实际费用填写 */
    private BigDecimal fee;

    /** 成交总额（元，人民币，含费净回款）：选填，传了直接作为本笔净回款，不再按费率/手续费公式计算 */
    private BigDecimal total;

    public Long getAssetId() {
        return assetId;
    }

    public void setAssetId(Long assetId) {
        this.assetId = assetId;
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

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public void setFee(BigDecimal fee) {
        this.fee = fee;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }
}
