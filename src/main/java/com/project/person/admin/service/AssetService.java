package com.project.person.admin.service;

import com.project.person.admin.common.BizException;
import com.project.person.admin.common.UserContext;
import com.project.person.admin.mapper.AssetMapper;
import com.project.person.admin.mapper.AssetValueHistoryMapper;
import com.project.person.admin.mapper.DailySnapshotMapper;
import com.project.person.admin.model.Asset;
import com.project.person.admin.model.AssetSell;
import com.project.person.admin.model.AssetValueHistory;
import com.project.person.admin.model.DailySnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AssetService {

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private AssetValueHistoryMapper valueHistoryMapper;

    @Autowired
    private AssetSupport assetSupport;

    @Autowired
    private DailySnapshotMapper snapshotMapper;

    @Autowired
    private SnapshotService snapshotService;

    public List<Asset> list() {
        return assetMapper.list(UserContext.getUserId());
    }

    @Transactional
    public Asset create(Asset asset) {
        checkAsset(asset);
        Long userId = UserContext.getUserId();
        if (asset.getChangeDate() == null) {
            asset.setChangeDate(LocalDate.now());
        }
        if (asset.getChangeDate().isAfter(LocalDate.now())) {
            throw BizException.badRequest("入账日期不能晚于今天");
        }
        asset.setId(null);
        asset.setUserId(userId);
        asset.setRemark(asset.getRemark() == null ? "" : asset.getRemark());
        assetMapper.insert(asset);
        // 入账日期早于今天：在该日补记快照，折线图从该月开始反映这笔资产
        if (asset.getChangeDate().isBefore(LocalDate.now())) {
            snapshotService.recordAt(userId, DailySnapshot.KIND_ASSET, asset.getId(),
                    asset.getName(), asset.getCategory(), asset.getChangeDate(), asset.getCurrentValue(), null);
        }
        snapshotService.recordToday(userId);
        return asset;
    }

    @Transactional
    public Asset update(Long id, Asset asset) {
        checkAsset(asset);
        Long userId = UserContext.getUserId();
        asset.setId(id);
        asset.setUserId(userId);
        if (assetMapper.update(asset) == 0) {
            throw BizException.badRequest("资产不存在或不属于当前用户");
        }
        // 编辑指定了生效日期：重定折线图起点——新日期之前的历史快照不再展示（否则旧起点残留，改晚不生效），
        // 再在新日期补记一条；留空则只反映到今日
        if (asset.getChangeDate() != null) {
            if (asset.getChangeDate().isAfter(LocalDate.now())) {
                throw BizException.badRequest("生效日期不能晚于今天");
            }
            snapshotMapper.deleteBefore(userId, DailySnapshot.KIND_ASSET, id, asset.getChangeDate());
            snapshotService.recordAt(userId, DailySnapshot.KIND_ASSET, id,
                    asset.getName(), asset.getCategory(), asset.getChangeDate(), asset.getCurrentValue(), null);
        }
        snapshotService.recordToday(userId);
        return asset;
    }

    /** 删除资产并清除与其相关的全部历史数据（估值历史 + 每日快照），折线图上整条移除 */
    @Transactional
    public void delete(Long id) {
        Long userId = UserContext.getUserId();
        Asset old = assetMapper.findById(id, userId);
        if (old == null) {
            throw BizException.badRequest("资产不存在或不属于当前用户");
        }
        assetMapper.delete(id, userId);
        valueHistoryMapper.deleteByAssetId(id, userId);
        snapshotService.purgeHistory(userId, DailySnapshot.KIND_ASSET, id);
        snapshotService.recordToday(userId);
    }

    /**
     * 卖出资产（仅非现金/银行卡类别，如房产、车辆、电子产品）：整体卖出。
     * 卖出价格（人民币）全额进入资金去向资产（必须是现金/银行卡），
     * 同时删除该资产及其全部历史数据（与个人相关的快照、估值历史一并清除）。
     */
    @Transactional
    public void sell(Long id, AssetSell sell) {
        Long userId = UserContext.getUserId();
        if (sell == null) {
            throw BizException.badRequest("卖出参数不能为空");
        }
        Asset old = assetMapper.findById(id, userId);
        if (old == null) {
            throw BizException.badRequest("资产不存在或不属于当前用户");
        }
        if ("现金".equals(old.getCategory()) || "银行卡".equals(old.getCategory())) {
            throw BizException.badRequest("现金/银行卡类资产不支持卖出，可直接编辑金额");
        }
        if (sell.getPrice() == null || sell.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.badRequest("请填写卖出价格");
        }
        Asset dest = assetSupport.requireCashAsset(userId, sell.getAssetId());
        assetMapper.adjustValue(dest.getId(), userId, sell.getPrice());
        assetMapper.delete(id, userId);
        valueHistoryMapper.deleteByAssetId(id, userId);
        snapshotService.purgeHistory(userId, DailySnapshot.KIND_ASSET, id);
        snapshotService.recordToday(userId);
    }

    /** 写入某日估值快照：同时更新资产当前值并记录当日快照 */
    @Transactional
    public void addValue(Long id, AssetValueHistory history) {
        Long userId = UserContext.getUserId();
        if (assetMapper.findById(id, userId) == null) {
            throw BizException.badRequest("资产不存在或不属于当前用户");
        }
        if (history == null || history.getValueDate() == null || history.getAssetValue() == null) {
            throw BizException.badRequest("valueDate 和 value 不能为空");
        }
        history.setUserId(userId);
        history.setAssetId(id);
        valueHistoryMapper.insert(history);
        assetMapper.setValue(id, userId, history.getAssetValue());
        snapshotService.recordToday(userId);
    }

    /**
     * 各资产 + 各投资 + 总资产走势聚合（读每日快照表）：
     * 每个月取该资产/投资「不晚于当月最后一天」的最新一条快照估值；无快照的月份补 0；
     * 已删除的资产/投资保留历史曲线并在清零日落到 0；投资序列名带「投资·」前缀，总资产含全部投资
     */
    public Map<String, Object> trend(String startMonth, String endMonth) {
        YearMonth startYm = IncomeService.parseMonth(startMonth, true);
        YearMonth endYm = IncomeService.parseMonth(endMonth, true);
        if (startYm.isAfter(endYm)) {
            throw BizException.badRequest("startMonth 不能晚于 endMonth");
        }
        Long userId = UserContext.getUserId();
        LocalDate rangeStart = startYm.atDay(1);
        LocalDate rangeEnd = endYm.atEndOfMonth();

        List<String> months = new ArrayList<>();
        for (YearMonth ym = startYm; !ym.isAfter(endYm); ym = ym.plusMonths(1)) {
            months.add(ym.toString());
        }

        List<DailySnapshot> assetSnaps = snapshotMapper.listByRange(userId, DailySnapshot.KIND_ASSET, rangeStart, rangeEnd);
        List<DailySnapshot> investSnaps = snapshotMapper.listByRange(userId, DailySnapshot.KIND_INVEST, rangeStart, rangeEnd);
        // kind + ":" + refId 作为 key，避免资产与投资 id 冲突
        Map<String, List<DailySnapshot>> byRef = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        collectSnapshots(assetSnaps, DailySnapshot.KIND_ASSET, byRef, names);
        collectSnapshots(investSnaps, DailySnapshot.KIND_INVEST, byRef, names);

        List<Map<String, Object>> assetPoints = new ArrayList<>();
        BigDecimal[] total = new BigDecimal[months.size()];
        for (int i = 0; i < total.length; i++) {
            total[i] = BigDecimal.ZERO;
        }
        for (Map.Entry<String, List<DailySnapshot>> entry : byRef.entrySet()) {
            List<BigDecimal> values = new ArrayList<>();
            for (int i = 0; i < months.size(); i++) {
                LocalDate monthEnd = YearMonth.parse(months.get(i)).atEndOfMonth();
                BigDecimal v = latestValueUpTo(entry.getValue(), monthEnd);
                values.add(v);
                total[i] = total[i].add(v);
            }
            Map<String, Object> item = new HashMap<>();
            item.put("id", entry.getKey());
            item.put("name", names.get(entry.getKey()));
            item.put("values", values);
            assetPoints.add(item);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("months", months);
        data.put("assets", assetPoints);
        data.put("total", java.util.Arrays.asList(total));
        return data;
    }

    /**
     * 各资产 + 各投资 + 总资产走势（按天粒度，读每日快照表）：
     * 每个交易日取当日快照估值；非交易日（周末/无快照日）沿用最近一次快照值；首个快照之前补 0；
     * 结构与 trend 相同，仅 months 换成 days（YYYY-MM-DD）
     */
    public Map<String, Object> trendDaily(String start, String end) {
        LocalDate rangeStart;
        LocalDate rangeEnd;
        try {
            rangeStart = LocalDate.parse(start);
            rangeEnd = LocalDate.parse(end);
        } catch (Exception e) {
            throw BizException.badRequest("start/end 格式应为 YYYY-MM-DD");
        }
        if (rangeStart.isAfter(rangeEnd)) {
            throw BizException.badRequest("start 不能晚于 end");
        }
        Long userId = UserContext.getUserId();

        List<String> days = new ArrayList<>();
        for (LocalDate d = rangeStart; !d.isAfter(rangeEnd); d = d.plusDays(1)) {
            days.add(d.toString());
        }

        List<DailySnapshot> assetSnaps = snapshotMapper.listByRange(userId, DailySnapshot.KIND_ASSET, rangeStart, rangeEnd);
        List<DailySnapshot> investSnaps = snapshotMapper.listByRange(userId, DailySnapshot.KIND_INVEST, rangeStart, rangeEnd);
        Map<String, List<DailySnapshot>> byRef = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        collectSnapshots(assetSnaps, DailySnapshot.KIND_ASSET, byRef, names);
        collectSnapshots(investSnaps, DailySnapshot.KIND_INVEST, byRef, names);

        List<Map<String, Object>> assetPoints = new ArrayList<>();
        BigDecimal[] total = new BigDecimal[days.size()];
        for (int i = 0; i < total.length; i++) {
            total[i] = BigDecimal.ZERO;
        }
        for (Map.Entry<String, List<DailySnapshot>> entry : byRef.entrySet()) {
            Map<LocalDate, BigDecimal> byDay = new HashMap<>();
            for (DailySnapshot s : entry.getValue()) {
                byDay.put(s.getSnapshotDate(), s.getTotalValue());
            }
            List<BigDecimal> values = new ArrayList<>();
            BigDecimal lastV = BigDecimal.ZERO;
            for (int i = 0; i < days.size(); i++) {
                BigDecimal v = byDay.get(LocalDate.parse(days.get(i)));
                if (v != null) {
                    lastV = v;
                }
                values.add(lastV);
                total[i] = total[i].add(lastV);
            }
            Map<String, Object> item = new HashMap<>();
            item.put("id", entry.getKey());
            item.put("name", names.get(entry.getKey()));
            item.put("values", values);
            assetPoints.add(item);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("days", days);
        data.put("assets", assetPoints);
        data.put("total", java.util.Arrays.asList(total));
        return data;
    }

    /** 汇总某类快照：分组并存显示名（投资加「投资·」前缀，与资产序列区分） */
    private void collectSnapshots(List<DailySnapshot> snapshots, String kind,
                                  Map<String, List<DailySnapshot>> byRef, Map<String, String> names) {
        for (DailySnapshot s : snapshots) {
            String key = kind + ":" + s.getRefId();
            byRef.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
            String name = s.getName() == null ? ("#" + s.getRefId()) : s.getName();
            if (DailySnapshot.KIND_INVEST.equals(kind)) {
                name = "投资·" + name;
            }
            names.putIfAbsent(key, name);
        }
    }

    /** 取不晚于 monthEnd 的最后一条快照估值；无则补 0 */
    private BigDecimal latestValueUpTo(List<DailySnapshot> snapshots, LocalDate monthEnd) {
        BigDecimal result = null;
        LocalDate bestDate = null;
        for (DailySnapshot s : snapshots) {
            if (!s.getSnapshotDate().isAfter(monthEnd)) {
                if (bestDate == null || s.getSnapshotDate().isAfter(bestDate)) {
                    bestDate = s.getSnapshotDate();
                    result = s.getTotalValue();
                }
            }
        }
        return result != null ? result : BigDecimal.ZERO;
    }

    private void checkAsset(Asset asset) {
        if (asset == null || !StringUtils.hasText(asset.getName())) {
            throw BizException.badRequest("资产名称不能为空");
        }
        if (!StringUtils.hasText(asset.getCategory())) {
            throw BizException.badRequest("资产类别不能为空");
        }
        if (asset.getCurrentValue() == null || asset.getCurrentValue().compareTo(BigDecimal.ZERO) < 0) {
            throw BizException.badRequest("资产估值不能为负");
        }
    }
}
