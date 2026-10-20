package com.project.person.admin.controller;

import com.project.person.admin.common.BizException;
import com.project.person.admin.common.Result;
import com.project.person.admin.service.AuthService;
import com.project.person.admin.service.StatsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 1. 鉴权 /api/auth  +  8. 统计 /api/stats
 */
@RestController
public class IndexController {

    @Autowired
    private AuthService authService;

    @Autowired
    private StatsService statsService;

    // ---------------- 鉴权 ----------------

    @PostMapping("/api/auth/register")
    public Result<Void> register(@RequestBody Map<String, String> body) {
        try {
            authService.register(body.get("username"), body.get("password"), body.get("nickname"));
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PostMapping("/api/auth/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        try {
            return Result.ok(authService.login(body.get("username"), body.get("password")));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    // 修改密码必须走鉴权拦截（/api/auth/** 是免登录的，不能放那里），从 token 中取当前用户
    @PostMapping("/api/user/password")
    public Result<Void> changePassword(@RequestBody Map<String, String> body) {
        try {
            authService.changePassword(body.get("oldPassword"), body.get("newPassword"));
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    // ---------------- 统计 ----------------

    @GetMapping("/api/stats/dashboard")
    public Result<Map<String, Object>> dashboard() {
        try {
            return Result.ok(statsService.dashboard());
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @GetMapping("/api/stats/income-expense")
    public Result<Object> incomeExpense(@RequestParam String startMonth,
                                        @RequestParam String endMonth) {
        try {
            return Result.ok(statsService.incomeExpense(startMonth, endMonth));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @GetMapping("/api/stats/expense-category")
    public Result<Object> expenseCategory(@RequestParam(required = false) String month) {
        try {
            return Result.ok(statsService.expenseCategory(month));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @GetMapping("/api/stats/income-category")
    public Result<Object> incomeCategory(@RequestParam(required = false) String month) {
        try {
            return Result.ok(statsService.incomeCategory(month));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }
}
