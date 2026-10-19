package com.project.person.admin.controller;

import com.project.person.admin.common.BizException;
import com.project.person.admin.common.Result;
import com.project.person.admin.model.ExchangeRate;
import com.project.person.admin.service.FxService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 汇率 /api/fx：美元兑人民币等 5 个币种的当日汇率（缓存）与手动刷新
 */
@RestController
@RequestMapping("/api/fx")
public class FxController {

    @Autowired
    private FxService fxService;

    @GetMapping("/list")
    public Result<Map<String, ExchangeRate>> list() {
        try {
            return Result.ok(fxService.getAll());
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PostMapping("/refresh")
    public Result<Void> refresh() {
        try {
            fxService.refresh();
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }
}
