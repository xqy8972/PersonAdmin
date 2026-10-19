package com.project.person.admin.service;

import com.project.person.admin.common.BizException;
import com.project.person.admin.common.UserContext;
import com.project.person.admin.mapper.BodyWeightMapper;
import com.project.person.admin.model.BodyWeight;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class WeightService {

    @Autowired
    private BodyWeightMapper bodyWeightMapper;

    public List<BodyWeight> list(String start, String end) {
        LocalDate startDate = (start != null && !start.isEmpty()) ? IncomeService.parseDate(start, "start") : null;
        LocalDate endDate = (end != null && !end.isEmpty()) ? IncomeService.parseDate(end, "end") : null;
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw BizException.badRequest("start 不能晚于 end");
        }
        return bodyWeightMapper.list(UserContext.getUserId(), startDate, endDate);
    }

    /** 记录体重：同用户同日期已存在时执行更新（upsert），保证一天一条 */
    public BodyWeight record(BodyWeight bodyWeight) {
        checkWeight(bodyWeight);
        Long userId = UserContext.getUserId();
        BodyWeight existing = bodyWeightMapper.findByDate(userId, bodyWeight.getRecordDate());
        if (existing != null) {
            existing.setWeight(bodyWeight.getWeight());
            existing.setNote(bodyWeight.getNote() == null ? "" : bodyWeight.getNote());
            bodyWeightMapper.update(existing);
            return existing;
        }
        bodyWeight.setId(null);
        bodyWeight.setUserId(userId);
        bodyWeight.setNote(bodyWeight.getNote() == null ? "" : bodyWeight.getNote());
        bodyWeightMapper.insert(bodyWeight);
        return bodyWeight;
    }

    public BodyWeight update(Long id, BodyWeight bodyWeight) {
        checkWeight(bodyWeight);
        bodyWeight.setId(id);
        bodyWeight.setUserId(UserContext.getUserId());
        if (bodyWeightMapper.update(bodyWeight) == 0) {
            throw BizException.badRequest("记录不存在或不属于当前用户");
        }
        return bodyWeight;
    }

    public void delete(Long id) {
        if (bodyWeightMapper.delete(id, UserContext.getUserId()) == 0) {
            throw BizException.badRequest("记录不存在或不属于当前用户");
        }
    }

    private void checkWeight(BodyWeight bodyWeight) {
        if (bodyWeight == null || bodyWeight.getRecordDate() == null) {
            throw BizException.badRequest("日期不能为空");
        }
        if (bodyWeight.getWeight() == null
                || bodyWeight.getWeight().compareTo(new BigDecimal("20")) < 0
                || bodyWeight.getWeight().compareTo(new BigDecimal("500")) > 0) {
            throw BizException.badRequest("体重应在 20~500 kg 之间");
        }
    }
}
