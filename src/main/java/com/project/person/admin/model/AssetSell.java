package com.project.person.admin.model;

import java.math.BigDecimal;

/**
 * 资产卖出参数：非现金/银行卡类资产（房产、车辆、电子产品等）整体卖出。
 * price=卖出价格（人民币），assetId=资金去向（必须是现金/银行卡类资产）。
 */
public class AssetSell {

    private BigDecimal price;
    private Long assetId;

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Long getAssetId() {
        return assetId;
    }

    public void setAssetId(Long assetId) {
        this.assetId = assetId;
    }
}
