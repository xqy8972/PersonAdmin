package com.project.person.admin.controller;

import com.project.person.admin.common.BizException;
import com.project.person.admin.common.Result;
import com.project.person.admin.model.Asset;
import com.project.person.admin.model.AssetSell;
import com.project.person.admin.model.AssetValueHistory;
import com.project.person.admin.service.AssetService;
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
 * 5. 资产 /api/asset
 */
@RestController
@RequestMapping("/api/asset")
public class AssetController {

    @Autowired
    private AssetService assetService;

    @GetMapping("/list")
    public Result<List<Asset>> list() {
        try {
            return Result.ok(assetService.list());
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PostMapping
    public Result<Asset> create(@RequestBody Asset asset) {
        try {
            return Result.ok(assetService.create(asset));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public Result<Asset> update(@PathVariable Long id, @RequestBody Asset asset) {
        try {
            return Result.ok(assetService.update(id, asset));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        try {
            assetService.delete(id);
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @GetMapping("/trend")
    public Result<Map<String, Object>> trend(@RequestParam String startMonth,
                                             @RequestParam String endMonth) {
        try {
            return Result.ok(assetService.trend(startMonth, endMonth));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /** 按天粒度查询资产走势（范围选择仍按月，曲线精确到日） */
    @GetMapping("/trend/daily")
    public Result<Map<String, Object>> trendDaily(@RequestParam String start,
                                                  @RequestParam String end) {
        try {
            return Result.ok(assetService.trendDaily(start, end));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PostMapping("/{id}/value")
    public Result<Void> addValue(@PathVariable Long id, @RequestBody AssetValueHistory history) {
        try {
            assetService.addValue(id, history);
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /** 卖出资产（仅非现金/银行卡类别）：卖出价格进入资金去向资产，历史数据一并清除 */
    @PostMapping("/{id}/sell")
    public Result<Void> sell(@PathVariable Long id, @RequestBody AssetSell sell) {
        try {
            assetService.sell(id, sell);
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }
}
