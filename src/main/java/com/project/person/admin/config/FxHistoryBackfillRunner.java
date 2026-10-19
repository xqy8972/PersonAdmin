package com.project.person.admin.config;

import com.project.person.admin.service.FxService;
import com.project.person.admin.service.InvestmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 历史汇率启动检查：库中没有 2025-01-01 起的每日汇率时，从 frankfurter 拉取回填，
 * 然后按交易台账全量重算份额型标的历史快照（逐日取当日汇率），让存量曲线与历史汇率口径对齐。
 * 晚于 SqlInitRunner(1) 与 InvestmentTradeMigrateRunner(2)，保证表结构与台账迁移已就绪。
 */
@Component
@Order(3)
public class FxHistoryBackfillRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FxHistoryBackfillRunner.class);

    @Autowired
    private FxService fxService;

    @Autowired
    private InvestmentService investmentService;

    @Override
    public void run(String... args) {
        try {
            if (!fxService.backfillHistory()) {
                return;
            }
            int rebuilt = investmentService.rebuildSnapshotsFromTradesAll();
            log.info("历史汇率回填完成，已按台账重算 {} 个标的历史快照", rebuilt);
        } catch (Exception e) {
            log.warn("历史汇率回填/快照重算失败: {}", e.getMessage());
        }
    }
}
