package com.project.person.admin.service;

import com.project.person.admin.common.BizException;
import com.project.person.admin.common.PageResult;
import com.project.person.admin.common.UserContext;
import com.project.person.admin.mapper.AssetMapper;
import com.project.person.admin.mapper.IncomeMapper;
import com.project.person.admin.model.Asset;
import com.project.person.admin.model.Income;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@Service
public class IncomeService {

    @Autowired
    private IncomeMapper incomeMapper;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private AssetSupport assetSupport;

    @Autowired
    private SnapshotService snapshotService;

    @Autowired
    private AssetFlowService assetFlowService;

    public PageResult<Income> list(String month, int pageNum, int pageSize, String keyword) {
        YearMonth ym = parseMonth(month, true);
        Long userId = UserContext.getUserId();
        int offset = (pageNum - 1) * pageSize;
        List<Income> list = incomeMapper.page(userId, ym.atDay(1), ym.plusMonths(1).atDay(1), keyword, pageSize, offset);
        long total = incomeMapper.count(userId, ym.atDay(1), ym.plusMonths(1).atDay(1), keyword);
        return new PageResult<>(list, total);
    }

    /** 新增收入：入账资产金额相应增加 */
    @Transactional
    public Income create(Income income) {
        checkIncome(income);
        Long userId = UserContext.getUserId();
        Asset asset = assetSupport.requireCashAsset(userId, income.getAssetId());
        income.setId(null);
        income.setUserId(userId);
        income.setRemark(income.getRemark() == null ? "" : income.getRemark());
        incomeMapper.insert(income);
        assetMapper.adjustValue(asset.getId(), userId, income.getAmount());
        assetFlowService.record(userId, asset.getId(), AssetFlowService.TYPE_INCOME,
                income.getAmount(), income.getRecordDate(), AssetFlowService.REF_INCOME, income.getId(), income.getCategory());
        snapshotService.recordToday(userId);
        return income;
    }

    /** 修改收入：先回滚旧资产，再应用到新资产 */
    @Transactional
    public Income update(Long id, Income income) {
        checkIncome(income);
        Long userId = UserContext.getUserId();
        Income old = incomeMapper.findById(id, userId);
        if (old == null) {
            throw BizException.badRequest("记录不存在或不属于当前用户");
        }
        Asset asset = assetSupport.requireCashAsset(userId, income.getAssetId());
        // 回滚旧流水再按新值补记（资产变动流水与单据保持一致）
        assetFlowService.rollback(userId, AssetFlowService.REF_INCOME, id);
        if (old.getAssetId() != null) {
            assetMapper.adjustValue(old.getAssetId(), userId, old.getAmount().negate());
        }
        assetMapper.adjustValue(asset.getId(), userId, income.getAmount());
        income.setId(id);
        income.setUserId(userId);
        if (incomeMapper.update(income) == 0) {
            throw BizException.badRequest("记录不存在或不属于当前用户");
        }
        assetFlowService.record(userId, asset.getId(), AssetFlowService.TYPE_INCOME,
                income.getAmount(), income.getRecordDate(), AssetFlowService.REF_INCOME, id, income.getCategory());
        snapshotService.recordToday(userId);
        return income;
    }

    /** 删除收入：资产金额相应回减 */
    @Transactional
    public void delete(Long id) {
        Long userId = UserContext.getUserId();
        Income old = incomeMapper.findById(id, userId);
        if (old == null) {
            throw BizException.badRequest("记录不存在或不属于当前用户");
        }
        if (old.getAssetId() != null && old.getAmount() != null) {
            assetMapper.adjustValue(old.getAssetId(), userId, old.getAmount().negate());
        }
        assetFlowService.rollback(userId, AssetFlowService.REF_INCOME, id);
        incomeMapper.delete(id, userId);
        snapshotService.recordToday(userId);
    }

    private void checkIncome(Income income) {
        if (income == null || income.getAmount() == null || income.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.badRequest("金额必须大于 0");
        }
        if (!StringUtils.hasText(income.getCategory())) {
            throw BizException.badRequest("分类不能为空");
        }
        if (income.getRecordDate() == null) {
            throw BizException.badRequest("收入日期不能为空");
        }
    }

    static YearMonth parseMonth(String month, boolean required) {
        if (!StringUtils.hasText(month)) {
            if (required) {
                throw BizException.badRequest("month 参数不能为空，格式 YYYY-MM");
            }
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(month);
        } catch (Exception e) {
            throw BizException.badRequest("month 格式错误，应为 YYYY-MM");
        }
    }

    static LocalDate parseDate(String date, String name) {
        try {
            return LocalDate.parse(date);
        } catch (Exception e) {
            throw BizException.badRequest(name + " 格式错误，应为 YYYY-MM-DD");
        }
    }
}
