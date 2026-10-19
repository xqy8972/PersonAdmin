package com.project.person.admin.config;

import com.project.person.admin.mapper.InvestmentMapper;
import com.project.person.admin.mapper.InvestmentTradeMapper;
import com.project.person.admin.model.Investment;
import com.project.person.admin.model.InvestmentTrade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 交易记录存量迁移：investment_trade 表上线前已存在的投资没有 BUY 记录，
 * 启动时为每笔「无任何交易记录且买入日非空」的投资补写一条 BUY，
 * 单价按 投入金额/份额 折算（人民币口径，港股标的存在币种近似）。
 * 晚于 SqlInitRunner（@Order(2) > @Order(1)），保证表结构与初始数据已就绪。
 */
@Component
@Order(2)
public class InvestmentTradeMigrateRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(InvestmentTradeMigrateRunner.class);

    @Autowired
    private InvestmentMapper investmentMapper;

    @Autowired
    private InvestmentTradeMapper tradeMapper;

    @Override
    public void run(String... args) {
        try {
            List<Investment> all = investmentMapper.listAll();
            int migrated = 0;
            for (Investment inv : all) {
                try {
                    if (inv.getBuyDate() == null
                            || tradeMapper.countByInvestment(inv.getId(), inv.getUserId()) > 0) {
                        continue;
                    }
                    InvestmentTrade trade = new InvestmentTrade();
                    trade.setUserId(inv.getUserId());
                    trade.setInvestmentId(inv.getId());
                    trade.setTradeType(InvestmentTrade.TYPE_BUY);
                    trade.setTradeDate(inv.getBuyDate());
                    trade.setShares(inv.getShares());
                    if (inv.getShares() != null && inv.getShares() > 0
                            && inv.getAmount() != null && inv.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                        trade.setPrice(inv.getAmount().divide(
                                BigDecimal.valueOf(inv.getShares()), 4, RoundingMode.HALF_UP));
                    }
                    trade.setAmount(inv.getAmount());
                    trade.setRemark("存量迁移");
                    tradeMapper.insert(trade);
                    migrated++;
                } catch (Exception e) {
                    log.warn("投资 [{}] BUY 记录迁移失败: {}", inv.getId(), e.getMessage());
                }
            }
            if (migrated > 0) {
                log.info("交易记录存量迁移完成：为 {} 笔投资补写 BUY 记录", migrated);
            }
        } catch (Exception e) {
            log.warn("交易记录存量迁移失败: {}", e.getMessage());
        }
    }
}
