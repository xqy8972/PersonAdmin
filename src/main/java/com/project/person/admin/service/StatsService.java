package com.project.person.admin.service;

import com.project.person.admin.common.UserContext;
import com.project.person.admin.mapper.AssetMapper;
import com.project.person.admin.mapper.ExpenseMapper;
import com.project.person.admin.mapper.IncomeMapper;
import com.project.person.admin.mapper.InvestmentMapper;
import com.project.person.admin.mapper.StatsMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StatsService {

    @Autowired
    private IncomeMapper incomeMapper;

    @Autowired
    private ExpenseMapper expenseMapper;

    @Autowired
    private InvestmentMapper investmentMapper;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private StatsMapper statsMapper;

    /** 主页汇总：本月收入 / 本月支出 / 资产总额 / 投资总额 / 最近 8 条收支 */
    public Map<String, Object> dashboard() {
        Long userId = UserContext.getUserId();
        LocalDate firstDayOfMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate recentSince = LocalDate.now().minusDays(30);

        Map<String, Object> data = new HashMap<>();
        data.put("monthIncome", incomeMapper.sumFrom(userId, firstDayOfMonth));
        data.put("monthExpense", expenseMapper.sumFrom(userId, firstDayOfMonth));
        data.put("assetTotal", assetMapper.sumCurrentValue(userId));
        data.put("investTotal", investmentMapper.sumCurrentValue(userId));

        List<Map<String, Object>> recent = new ArrayList<>();
        for (Map<String, Object> row : statsMapper.recentRecords(userId, recentSince)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", row.get("KIND"));
            item.put("amount", row.get("AMOUNT"));
            item.put("category", row.get("CATEGORY"));
            Object recordDate = row.get("RECORD_DATE");
            item.put("recordDate", recordDate instanceof Date ? ((Date) recordDate).toLocalDate() : recordDate);
            recent.add(item);
        }
        data.put("recent", recent);
        return data;
    }

    /** 收支趋势：按月聚合，缺月补 0 */
    public List<Map<String, Object>> incomeExpense(String startMonth, String endMonth) {
        YearMonth startYm = IncomeService.parseMonth(startMonth, true);
        YearMonth endYm = IncomeService.parseMonth(endMonth, true);
        if (startYm.isAfter(endYm)) {
            throw com.project.person.admin.common.BizException.badRequest("startMonth 不能晚于 endMonth");
        }
        Long userId = UserContext.getUserId();
        LocalDate start = startYm.atDay(1);
        LocalDate end = endYm.plusMonths(1).atDay(1);

        Map<String, BigDecimal> incomeMap = toMonthMap(incomeMapper.sumByMonth(userId, start, end));
        Map<String, BigDecimal> expenseMap = toMonthMap(expenseMapper.sumByMonth(userId, start, end));

        List<Map<String, Object>> data = new ArrayList<>();
        for (YearMonth ym = startYm; !ym.isAfter(endYm); ym = ym.plusMonths(1)) {
            String month = ym.toString();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("month", month);
            item.put("income", incomeMap.getOrDefault(month, BigDecimal.ZERO));
            item.put("expense", expenseMap.getOrDefault(month, BigDecimal.ZERO));
            data.add(item);
        }
        return data;
    }

    /** 支出分类饼图：month 缺省当月 */
    public List<Map<String, Object>> expenseCategory(String month) {
        YearMonth ym = IncomeService.parseMonth(month, false);
        Long userId = UserContext.getUserId();
        List<Map<String, Object>> rows = expenseMapper.sumByCategory(userId, ym.atDay(1), ym.plusMonths(1).atDay(1));
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", row.get("CATEGORY"));
            item.put("amount", row.get("TOTAL"));
            data.add(item);
        }
        return data;
    }

    private Map<String, BigDecimal> toMonthMap(List<Map<String, Object>> rows) {
        Map<String, BigDecimal> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            map.put(String.valueOf(row.get("YM")), (BigDecimal) row.get("TOTAL"));
        }
        return map;
    }
}
