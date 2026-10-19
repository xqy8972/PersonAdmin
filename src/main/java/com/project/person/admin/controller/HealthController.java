package com.project.person.admin.controller;

import com.project.person.admin.common.BizException;
import com.project.person.admin.common.Result;
import com.project.person.admin.model.BodyWeight;
import com.project.person.admin.model.DietRecord;
import com.project.person.admin.service.DietService;
import com.project.person.admin.service.WeightService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 6. 体重 /api/weight  +  7. 饮食 /api/diet
 */
@RestController
public class HealthController {

    @Autowired
    private WeightService weightService;

    @Autowired
    private DietService dietService;

    // ---------------- 体重 ----------------

    @GetMapping("/api/weight/list")
    public Result<List<BodyWeight>> weightList(@RequestParam(required = false) String start,
                                               @RequestParam(required = false) String end) {
        try {
            return Result.ok(weightService.list(start, end));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PostMapping("/api/weight")
    public Result<BodyWeight> recordWeight(@RequestBody BodyWeight bodyWeight) {
        try {
            return Result.ok(weightService.record(bodyWeight));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PutMapping("/api/weight/{id}")
    public Result<BodyWeight> updateWeight(@PathVariable Long id, @RequestBody BodyWeight bodyWeight) {
        try {
            return Result.ok(weightService.update(id, bodyWeight));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @DeleteMapping("/api/weight/{id}")
    public Result<Void> deleteWeight(@PathVariable Long id) {
        try {
            weightService.delete(id);
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    // ---------------- 饮食 ----------------

    @GetMapping("/api/diet/list")
    public Result<List<DietRecord>> dietList(@RequestParam String date) {
        try {
            return Result.ok(dietService.list(date));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @GetMapping("/api/diet/daily-summary")
    public Result<List<Map<String, Object>>> dietDailySummary(@RequestParam String start,
                                                              @RequestParam String end) {
        try {
            return Result.ok(dietService.dailySummary(start, end));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PostMapping("/api/diet")
    public Result<DietRecord> createDiet(@RequestBody DietRecord dietRecord) {
        try {
            return Result.ok(dietService.create(dietRecord));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PutMapping("/api/diet/{id}")
    public Result<DietRecord> updateDiet(@PathVariable Long id, @RequestBody DietRecord dietRecord) {
        try {
            return Result.ok(dietService.update(id, dietRecord));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @DeleteMapping("/api/diet/{id}")
    public Result<Void> deleteDiet(@PathVariable Long id) {
        try {
            dietService.delete(id);
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }
}
