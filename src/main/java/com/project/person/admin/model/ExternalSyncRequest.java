package com.project.person.admin.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 外部渠道持仓同步推送请求（由本机辅助程序如 THSAutoTrader 的胶水脚本上报）。
 * account 必须与后台登记的渠道账户名一致，否则拒绝落库。
 */
public class ExternalSyncRequest {

    /** 渠道标识：THS=同花顺 */
    private String channel;
    /** 渠道账户名（校验用） */
    private String account;
    /** 当前持仓列表 */
    private List<Position> positions;

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public List<Position> getPositions() {
        return positions;
    }

    public void setPositions(List<Position> positions) {
        this.positions = positions;
    }

    /** 单条持仓：code 需为归一化代码（sh600519/sz000001/hk01810），由推送脚本转换 */
    public static class Position {
        private String code;
        private String name;
        private Integer shares;
        /** 成本价（每股，股票币种；港股为港币） */
        private BigDecimal costPrice;

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

        public Integer getShares() {
            return shares;
        }

        public void setShares(Integer shares) {
            this.shares = shares;
        }

        public BigDecimal getCostPrice() {
            return costPrice;
        }

        public void setCostPrice(BigDecimal costPrice) {
            this.costPrice = costPrice;
        }
    }
}
