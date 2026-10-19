package com.project.person.admin.task;

import com.project.person.admin.service.FxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 汇率定时任务：每 10 分钟拉取一次美元兑人民币等 5 个币种汇率，
 * 当日值更新缓存，历史按日落库。外汇市场工作日全天有效，无需交易时段判断。
 */
@Component
public class FxPullTask {

    private static final Logger log = LoggerFactory.getLogger(FxPullTask.class);

    @Autowired
    private FxService fxService;

    @Scheduled(fixedDelay = 600000, initialDelay = 60000)
    public void pull() {
        try {
            fxService.refresh();
        } catch (Exception e) {
            log.warn("定时汇率拉取失败: {}", e.getMessage());
        }
    }
}
