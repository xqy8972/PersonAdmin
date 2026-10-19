package com.project.person.admin.service;

import com.project.person.admin.common.BizException;
import com.project.person.admin.common.UserContext;
import com.project.person.admin.mapper.DietRecordMapper;
import com.project.person.admin.model.DietRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DietService {

    @Autowired
    private DietRecordMapper dietRecordMapper;

    /** 按天查询饮食明细 */
    public List<DietRecord> list(String date) {
        if (!StringUtils.hasText(date)) {
            throw BizException.badRequest("date 参数不能为空，格式 YYYY-MM-DD");
        }
        return dietRecordMapper.listByDate(UserContext.getUserId(), IncomeService.parseDate(date, "date"));
    }

    /** 每日热量汇总（热量走势图） */
    public List<Map<String, Object>> dailySummary(String start, String end) {
        if (!StringUtils.hasText(start) || !StringUtils.hasText(end)) {
            throw BizException.badRequest("start 和 end 参数不能为空，格式 YYYY-MM-DD");
        }
        LocalDate startDate = IncomeService.parseDate(start, "start");
        LocalDate endDate = IncomeService.parseDate(end, "end");
        if (startDate.isAfter(endDate)) {
            throw BizException.badRequest("start 不能晚于 end");
        }
        List<Map<String, Object>> rows = dietRecordMapper.dailyCalories(UserContext.getUserId(), startDate, endDate);
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new HashMap<>();
            item.put("date", row.get("RECORDDATE"));
            item.put("calories", row.get("CALORIES"));
            data.add(item);
        }
        return data;
    }

    public DietRecord create(DietRecord dietRecord) {
        checkDiet(dietRecord);
        dietRecord.setId(null);
        dietRecord.setUserId(UserContext.getUserId());
        dietRecord.setRemark(dietRecord.getRemark() == null ? "" : dietRecord.getRemark());
        dietRecordMapper.insert(dietRecord);
        return dietRecord;
    }

    public DietRecord update(Long id, DietRecord dietRecord) {
        checkDiet(dietRecord);
        dietRecord.setId(id);
        dietRecord.setUserId(UserContext.getUserId());
        if (dietRecordMapper.update(dietRecord) == 0) {
            throw BizException.badRequest("记录不存在或不属于当前用户");
        }
        return dietRecord;
    }

    public void delete(Long id) {
        if (dietRecordMapper.delete(id, UserContext.getUserId()) == 0) {
            throw BizException.badRequest("记录不存在或不属于当前用户");
        }
    }

    private void checkDiet(DietRecord dietRecord) {
        if (dietRecord == null || dietRecord.getRecordDate() == null) {
            throw BizException.badRequest("日期不能为空");
        }
        if (!StringUtils.hasText(dietRecord.getMealType())) {
            throw BizException.badRequest("餐次不能为空");
        }
        if (!StringUtils.hasText(dietRecord.getFood())) {
            throw BizException.badRequest("食物名称不能为空");
        }
        if (dietRecord.getCalories() == null || dietRecord.getCalories() < 0) {
            throw BizException.badRequest("热量不能为负");
        }
    }
}
