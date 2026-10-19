package com.project.person.admin.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 行情缓存对象：股票/基金的最新报价。
 * 股票：price=最新价（非交易时段为最近收盘价），prevPrice=昨收；
 * 基金：price=盘中估算净值(gsz)，prevPrice=昨日单位净值(dwjz)，changePercent=估算涨幅(%)。
 */
public class MarketQuote {

    public static final String TYPE_STOCK = "STOCK";
    public static final String TYPE_FUND = "FUND";

    private String code;
    private String name;
    private String type;
    private BigDecimal price;
    private BigDecimal prevPrice;
    private BigDecimal changePercent;
    private LocalDateTime updateTime;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
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

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getPrevPrice() {
        return prevPrice;
    }

    public void setPrevPrice(BigDecimal prevPrice) {
        this.prevPrice = prevPrice;
    }

    public BigDecimal getChangePercent() {
        return changePercent;
    }

    public void setChangePercent(BigDecimal changePercent) {
        this.changePercent = changePercent;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
