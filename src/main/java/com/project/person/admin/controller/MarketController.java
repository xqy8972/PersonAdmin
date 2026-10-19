package com.project.person.admin.controller;

import com.project.person.admin.common.BizException;
import com.project.person.admin.common.Result;
import com.project.person.admin.model.MarketQuote;
import com.project.person.admin.service.MarketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 股票/基金实时行情 /api/market
 * （缓存由 MarketValuePullTask 每 20s 刷新；本 Controller 只做查询与手动触发）
 */
@RestController
@RequestMapping("/api/market")
public class MarketController {

    @Autowired
    private MarketService marketService;

    /** 手动刷新行情缓存（盘中随时点） */
    @PostMapping("/refresh")
    public Result<Void> refresh() {
        try {
            marketService.refresh();
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /** 手动触发当日收盘落库（错过 15:05 定时任务时补录） */
    @PostMapping("/record-daily")
    public Result<Void> recordDaily() {
        try {
            marketService.recordDailyClose();
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /** 查询单个标缓存行情 */
    @GetMapping("/quote/{code}")
    public Result<MarketQuote> quote(@PathVariable String code) {
        try {
            return Result.ok(marketService.getQuote(code));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /** 缓存中的全部行情 */
    @GetMapping("/list")
    public Result<Map<String, MarketQuote>> list() {
        try {
            return Result.ok(marketService.getAllQuotes());
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /** 校验代码是否存在（新增投资前端防抖搜索用）：可按投资类型传裸代码，自动补市场前缀；合法则同时加入缓存 */
    @PostMapping("/validate")
    public Result<MarketQuote> validate(@RequestBody Map<String, String> body) {
        try {
            String code = body == null ? null : body.get("code");
            String type = body == null ? null : body.get("type");
            if (StringUtils.hasText(type) && StringUtils.hasText(code)) {
                code = marketService.resolveCode(type, code);
            }
            return Result.ok(marketService.validateAndCache(code));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }
}
