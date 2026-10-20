package com.project.person.admin.controller;

import com.project.person.admin.common.BizException;
import com.project.person.admin.common.PageResult;
import com.project.person.admin.common.Result;
import com.project.person.admin.model.Expense;
import com.project.person.admin.service.ExpenseService;
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
 * 3. 支出 /api/expense
 */
@RestController
@RequestMapping("/api/expense")
public class ExpenseController {

    @Autowired
    private ExpenseService expenseService;

    @GetMapping("/list")
    public Result<PageResult<Expense>> list(@RequestParam String month,
                                            @RequestParam(defaultValue = "1") int pageNum,
                                            @RequestParam(defaultValue = "10") int pageSize,
                                            @RequestParam(required = false) String keyword) {
        try {
            return Result.ok(expenseService.list(month, pageNum, pageSize, keyword));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PostMapping
    public Result<Expense> create(@RequestBody Expense expense) {
        try {
            return Result.ok(expenseService.create(expense));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /**
     * AI 识图解析支出：一次可传多张图片（≤9），图片不保存、实时调 AI。
     * 返回待确认结果列表（不落库不扣款），前端展示供用户检查后逐条走正常创建。
     */
    @PostMapping("/recognize")
    public Result<java.util.List<com.project.person.admin.model.RecognizedExpense>> recognize(
            @RequestParam("files") org.springframework.web.multipart.MultipartFile[] files) {
        try {
            return Result.ok(expenseService.recognize(files));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /**
     * OCR 文本解析支出：前端本地 OCR 先提取图片文字，再交 AI 纯文本解析（更快、更省）。
     * 请求体：{"texts": ["第1张图的OCR文本", ...]}（≤9 段）。
     */
    @PostMapping("/recognize-text")
    public Result<java.util.List<com.project.person.admin.model.RecognizedExpense>> recognizeText(
            @RequestBody java.util.Map<String, Object> body) {
        try {
            Object texts = body == null ? null : body.get("texts");
            java.util.List<String> list = new java.util.ArrayList<>();
            if (texts instanceof java.util.List) {
                for (Object t : (java.util.List<?>) texts) {
                    if (t != null && !t.toString().trim().isEmpty()) {
                        list.add(t.toString());
                    }
                }
            }
            return Result.ok(expenseService.recognizeFromTexts(list));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public Result<Expense> update(@PathVariable Long id, @RequestBody Expense expense) {
        try {
            return Result.ok(expenseService.update(id, expense));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        try {
            expenseService.delete(id);
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }
}
