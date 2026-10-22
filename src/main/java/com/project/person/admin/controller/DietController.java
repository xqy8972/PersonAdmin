package com.project.person.admin.controller;

import com.project.person.admin.common.BizException;
import com.project.person.admin.common.Result;
import com.project.person.admin.model.DietRecord;
import com.project.person.admin.service.CalorieRecognizeService;
import com.project.person.admin.service.DietService;
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
 * 健康：饮食记录 /api/diet
 */
@RestController
@RequestMapping("/api/diet")
public class DietController {

    @Autowired
    private DietService dietService;

    @Autowired
    private CalorieRecognizeService calorieRecognizeService;

    /** 按天查询饮食明细 */
    @GetMapping("/list")
    public Result<List<DietRecord>> list(@RequestParam String date) {
        try {
            return Result.ok(dietService.list(date));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /** 每日热量汇总（热量走势图） */
    @GetMapping("/daily-summary")
    public Result<List<Map<String, Object>>> dailySummary(@RequestParam String start,
                                                          @RequestParam String end) {
        try {
            return Result.ok(dietService.dailySummary(start, end));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PostMapping
    public Result<DietRecord> create(@RequestBody DietRecord dietRecord) {
        try {
            return Result.ok(dietService.create(dietRecord));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public Result<DietRecord> update(@PathVariable Long id, @RequestBody DietRecord dietRecord) {
        try {
            return Result.ok(dietService.update(id, dietRecord));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        try {
            dietService.delete(id);
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /**
     * AI 识别饮食图片（食物/配料表/营养成分表，≤9 张）：返回会话 ID + 待确认食物清单。
     * 不落库；会话历史按用户隔离存缓存（2 小时），供后续对话修正使用。
     */
    @PostMapping("/recognize")
    public Result<CalorieRecognizeService.RecognizeResult> recognize(
            @RequestParam("files") org.springframework.web.multipart.MultipartFile[] files) {
        try {
            return Result.ok(calorieRecognizeService.recognize(files));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /**
     * OCR 文本识别：前端本地 OCR 先提取图片文字再交 AI 纯文本解析（更快更省）。
     * 请求体：{"texts": ["第1张图的OCR文本", ...]}（≤9 段）。
     */
    @PostMapping("/recognize-text")
    public Result<CalorieRecognizeService.RecognizeResult> recognizeText(
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
            return Result.ok(calorieRecognizeService.recognizeFromTexts(list));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /**
     * 对话修正/补充：{"convId": "xxx(可空)", "message": "那不是米饭，是面条"}。
     * convId 为空时以纯文字描述开启新会话。返回修正后的全量食物清单（幂等全量态）。
     */
    @PostMapping("/recognize/chat")
    public Result<CalorieRecognizeService.RecognizeResult> recognizeChat(
            @RequestBody java.util.Map<String, Object> body) {
        try {
            Object convId = body == null ? null : body.get("convId");
            Object message = body == null ? null : body.get("message");
            return Result.ok(calorieRecognizeService.chat(
                    convId == null ? null : convId.toString(),
                    message == null ? null : message.toString()));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }
}
