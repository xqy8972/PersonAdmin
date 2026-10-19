package com.project.person.admin.controller;

import com.project.person.admin.common.BizException;
import com.project.person.admin.common.Result;
import com.project.person.admin.model.Investment;
import com.project.person.admin.model.InvestmentBuy;
import com.project.person.admin.model.InvestmentSell;
import com.project.person.admin.model.InvestmentTrade;
import com.project.person.admin.model.InvestmentValueHistory;
import com.project.person.admin.service.InvestmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 4. 投资 /api/investment
 */
@RestController
@RequestMapping("/api/investment")
public class InvestmentController {

    @Autowired
    private InvestmentService investmentService;

    @GetMapping("/list")
    public Result<List<Investment>> list() {
        try {
            return Result.ok(investmentService.list());
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PostMapping
    public Result<Investment> create(@RequestBody Investment investment) {
        try {
            return Result.ok(investmentService.create(investment));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public Result<Investment> update(@PathVariable Long id, @RequestBody Investment investment) {
        try {
            return Result.ok(investmentService.update(id, investment));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        try {
            investmentService.delete(id);
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @GetMapping("/{id}/trend")
    public Result<List<Map<String, Object>>> trend(@PathVariable Long id,
                                                   @RequestParam(required = false) String start,
                                                   @RequestParam(required = false) String end) {
        try {
            return Result.ok(investmentService.trend(id, start, end));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /** 盈亏走势：按天聚合所有投资的（总市值 - 累计投入） */
    @GetMapping("/profit-trend")
    public Result<List<Map<String, Object>>> profitTrend(@RequestParam(required = false) String start,
                                                         @RequestParam(required = false) String end) {
        try {
            return Result.ok(investmentService.profitTrend(start, end));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /** 买卖交易记录：K 线买卖标记数据源，按日期升序 */
    @GetMapping("/{id}/trades")
    public Result<List<InvestmentTrade>> trades(@PathVariable Long id) {
        try {
            return Result.ok(investmentService.trades(id));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /** 买入后的历史盈亏（实时计算不持久化）：逐日 市值 - 持仓成本 */
    @GetMapping("/{id}/profit-history")
    public Result<List<Map<String, Object>>> profitHistory(@PathVariable Long id,
                                                           @RequestParam(required = false) String start,
                                                           @RequestParam(required = false) String end) {
        try {
            return Result.ok(investmentService.profitHistory(id, start, end));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PostMapping("/{id}/value")
    public Result<Void> addValue(@PathVariable Long id, @RequestBody InvestmentValueHistory history) {
        try {
            investmentService.addValue(id, history);
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PostMapping("/{id}/sell")
    public Result<Void> sell(@PathVariable Long id, @RequestBody InvestmentSell sell) {
        try {
            investmentService.sell(id, sell);
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /** 买入（加仓）：股票/基金按股数/份额+单价，买入日期默认今天，可选出资资产 */
    @PostMapping("/{id}/buy")
    public Result<Void> buy(@PathVariable Long id, @RequestBody InvestmentBuy buy) {
        try {
            investmentService.buy(id, buy);
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }
}
