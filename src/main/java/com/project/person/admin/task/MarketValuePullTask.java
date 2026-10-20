package com.project.person.admin.task;

import com.project.person.admin.service.MarketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 行情拉取定时任务：每 10s 触发一次，仅交易时段真正拉取（非交易时段缓存保留最近数据，
 * 投资列表仍按最近收盘价/估算展示）。第三方接口无 SLA，异常静默跳过等待下一轮。
 */
@Component
public class MarketValuePullTask {

    private static final Logger log = LoggerFactory.getLogger(MarketValuePullTask.class);

    @Autowired
    private MarketService marketService;

    @Scheduled(fixedDelay = 10000, initialDelay = 30000)
    public void pull() {
        try {
            // 按各市场开收盘时段选择性拉取（A 股/港股时间不同）
            marketService.refreshIfTrading();
        } catch (Exception e) {
            log.warn("定时行情拉取失败: {}", e.getMessage());
        }
    }
}
