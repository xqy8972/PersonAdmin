package com.project.person.admin.service;

import com.project.person.admin.mapper.AssetFlowMapper;
import com.project.person.admin.model.AssetFlow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 资产变动流水：所有资产余额变化（收入/支出/投资买卖/资产卖出/手动调整）统一经此落账，
 * 流向与金额跟随业务单据的日期（如补录历史交易，流水日期=交易日期）。
 */
@Service
public class AssetFlowService {

    public static final String TYPE_INCOME = "收入";
    public static final String TYPE_EXPENSE = "支出";
    public static final String TYPE_INVEST_BUY = "投资买入";
    public static final String TYPE_INVEST_SELL = "投资卖出";
    public static final String TYPE_INVEST_EDIT = "投资编辑";
    public static final String TYPE_INVEST_DELETE = "投资删除";
    public static final String TYPE_ASSET_SELL = "资产卖出";
    public static final String TYPE_ASSET_ADJUST = "资产调整";

    public static final String REF_INCOME = "income";
    public static final String REF_EXPENSE = "expense";
    public static final String REF_INVESTMENT = "investment";
    public static final String REF_INVEST_TRADE = "investment_trade";
    public static final String REF_INVEST_EDIT = "investment_edit";
    public static final String REF_ASSET = "asset";
    public static final String REF_ASSET_EDIT = "asset_edit";

    @Autowired
    private AssetFlowMapper assetFlowMapper;

    /** 记一笔资产变动（amount 带符号：正流入/负流出；flowDate 缺省今天） */
    public void record(Long userId, Long assetId, String flowType, BigDecimal amount,
                       LocalDate flowDate, String refType, Long refId, String remark) {
        if (assetId == null || amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        AssetFlow flow = new AssetFlow();
        flow.setUserId(userId);
        flow.setAssetId(assetId);
        flow.setFlowType(flowType);
        flow.setAmount(amount);
        flow.setFlowDate(flowDate != null ? flowDate : LocalDate.now());
        flow.setRefType(refType);
        flow.setRefId(refId);
        flow.setRemark(remark == null ? "" : remark);
        assetFlowMapper.insert(flow);
    }

    /** 回滚某单据产生的全部流水（编辑/删除单据时先调） */
    public void rollback(Long userId, String refType, Long refId) {
        assetFlowMapper.deleteByRef(userId, refType, refId);
    }

    /** 清除某资产的全部流水（资产删除/卖出时连同历史一并清除） */
    public void purgeByAsset(Long userId, Long assetId) {
        assetFlowMapper.deleteByAsset(userId, assetId);
    }
}
