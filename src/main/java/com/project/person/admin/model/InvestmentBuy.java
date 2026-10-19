package com.project.person.admin.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 投资买入（加仓）入参：股票/基金按股数/份额+单价；assetId 为出资资产（现金/银行卡，选填） */
public class InvestmentBuy {

    /** 出资资产 id（JSON 字段名 assetId）；选填，不选则不从任何资产扣款（外部资金/存续录入） */
    @com.fasterxml.jackson.annotation.JsonProperty("assetId")
    private Long assetId;

    /** 买入股数/份额 */
    private Integer shares;

    /** 买入单价（股票为对应币种：港股填港币；基金净值为人民币） */
    private BigDecimal price;

    /** 买入日期，默认今天；填过去日期用于补录历史买入（交易记录与快照按该日期落） */
    private LocalDate tradeDate;

    /** 本笔固定手续费（元，人民币）：选填，传了覆盖投资上的默认值，照成交单实际费用填写 */
    private BigDecimal fee;

    /** 成交总额（元，人民币，含费总扣款）：选填，传了直接作为本笔成本，不再按费率/手续费公式计算 */
    private BigDecimal total;

    /** 备注 */
    private String remark;

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

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
