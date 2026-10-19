package com.project.person.admin.task;

import com.project.person.admin.service.MarketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日收盘落库任务：区分市场开收盘时间——
 * A 股（含场外基金）15:05（收盘后 5 分钟），港股 16:05（收盘后 5 分钟），
 * 各市场独立触发，把当日收盘价/基金估算净值落库 market_quote 并回写投资市值。
 */
@Component
public class DailyMarketValueRecordTask {

    private static final Logger log = LoggerFactory.getLogger(DailyMarketValueRecordTask.class);

    @Autowired
    private MarketService marketService;

    /**
     * 每日收盘落库任务：区分市场开收盘时间——
     * A 股（含场外基金）15:05（收盘后 5 分钟），港股 16:05（收盘后 5 分钟），
     * 各市场独立触发，把当日收盘价/基金估算净值落库 market_quote 并回写投资市值。
     */
    @Scheduled(cron = "0 5 15 ? * MON-FRI")
    public void recordCn() {
        try {
            marketService.recordDailyClose(MarketService.MARKET_CN);
        } catch (Exception e) {
            log.error("A股收盘行情落库失败: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "0 5 16 ? * MON-FRI")
    public void recordHk() {
        try {
            marketService.recordDailyClose(MarketService.MARKET_HK);
        } catch (Exception e) {
            log.error("港股收盘行情落库失败: {}", e.getMessage(), e);
        }
    }
}
