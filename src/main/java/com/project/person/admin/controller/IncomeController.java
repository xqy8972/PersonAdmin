package com.project.person.admin.controller;

import com.project.person.admin.common.BizException;
import com.project.person.admin.common.PageResult;
import com.project.person.admin.common.Result;
import com.project.person.admin.model.Income;
import com.project.person.admin.service.IncomeService;
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

/**
 * 2. 收入 /api/income
 */
@RestController
@RequestMapping("/api/income")
public class IncomeController {

    @Autowired
    private IncomeService incomeService;

    @GetMapping("/list")
    public Result<PageResult<Income>> list(@RequestParam String month,
                                           @RequestParam(defaultValue = "1") int pageNum,
                                           @RequestParam(defaultValue = "10") int pageSize,
                                           @RequestParam(required = false) String keyword) {
        try {
            return Result.ok(incomeService.list(month, pageNum, pageSize, keyword));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PostMapping
    public Result<Income> create(@RequestBody Income income) {
        try {
            return Result.ok(incomeService.create(income));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public Result<Income> update(@PathVariable Long id, @RequestBody Income income) {
        try {
            return Result.ok(incomeService.update(id, income));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        try {
            incomeService.delete(id);
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }
}
