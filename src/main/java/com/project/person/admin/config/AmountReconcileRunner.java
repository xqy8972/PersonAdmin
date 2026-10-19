package com.project.person.admin.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 投资投入金额台账重算（启动自愈）：investment.amount 的唯一权威来源是
 * investment_trade 台账净额（Σ买 − Σ卖，摊薄口径，与同花顺一致）。
 * 每次启动用台账把非渠道标的的 amount 重算一遍，历史手工修正/旧逻辑写入的
 * 不一致值自动纠正；渠道同步标的（sourceChannel 非空，无台账）跳过。
 * 必须晚于 SqlInitRunner（@Order(1)）与 DataInitializer（@Order(2)）。
 */
@Component
@Order(3)
public class AmountReconcileRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AmountReconcileRunner.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        int updated = jdbcTemplate.update(
                "UPDATE investment i SET i.amount = (" +
                "  SELECT IFNULL(SUM(IF(t.trade_type = 'BUY', t.amount, -t.amount)), 0)" +
                "  FROM investment_trade t WHERE t.investment_id = i.id)" +
                "WHERE i.source_channel IS NULL OR i.source_channel = ''");
        log.info("投资投入金额台账重算完成，共校正 {} 条非渠道标的", updated);
    }
}
