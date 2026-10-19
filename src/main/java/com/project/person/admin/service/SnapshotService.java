package com.project.person.admin.service;

import com.project.person.admin.mapper.AssetMapper;
import com.project.person.admin.mapper.DailySnapshotMapper;
import com.project.person.admin.mapper.InvestmentMapper;
import com.project.person.admin.model.Asset;
import com.project.person.admin.model.DailySnapshot;
import com.project.person.admin.model.Investment;
import com.project.person.admin.model.MarketQuote;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 每日快照服务：以天维度记录资产/投资市值变化。
 * 任何联动变动（收支、投资买卖、资产增删改）后调用 recordToday；
 * 删除/卖出时调用 purgeHistory 清除该标的全部历史快照（整条移除）。
 */
@Service
public class SnapshotService {

    @Autowired
    private DailySnapshotMapper snapshotMapper;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private InvestmentMapper investmentMapper;

    /** @Lazy 避免与 MarketService 形成构造环（MarketService 也依赖本类） */
    @Autowired
    @Lazy
    private MarketService marketService;

    /** 记录今日全部资产和投资的当前市值（已存在的当日记录被覆盖更新） */
    public void recordToday(Long userId) {
        LocalDate today = LocalDate.now();
        for (Asset a : assetMapper.list(userId)) {
            DailySnapshot s = new DailySnapshot();
            s.setUserId(userId);
            s.setSnapshotDate(today);
            s.setKind(DailySnapshot.KIND_ASSET);
            s.setRefId(a.getId());
            s.setName(a.getName());
            s.setType(a.getCategory());
            s.setTotalValue(a.getCurrentValue());
            snapshotMapper.upsert(s);
        }
        for (Investment i : investmentMapper.list(userId)) {
            DailySnapshot s = new DailySnapshot();
            s.setUserId(userId);
            s.setSnapshotDate(today);
            s.setKind(DailySnapshot.KIND_INVEST);
            s.setRefId(i.getId());
            s.setName(i.getName());
            s.setType(i.getType());
            s.setTotalValue(i.getCurrentValue());
            // 记下当日累计投入，盈亏曲线 = 市值 - 投入
            s.setInvestAmount(i.getAmount());
            if (i.getShares() != null && i.getShares() > 0 && i.getCurrentValue() != null) {
                // 份额型带码标的：市值优先按最新行情价（缓存→历史K线兜底）计算，
                // 不能直接用库里的 current_value——它只在收盘落库时刷新，
                // 服务重启后未等到收盘落库前是旧的申报成本，会把今日快照覆盖成断崖。
                // 单价一律存「标的币种原始价」（港币股存港币）——与历史K线回填口径一致，
                // 两个交易日的价差×汇率才是人民币盈亏；存折人民币价会污染价差计算
                BigDecimal rawPrice = latestRawPrice(userId, i, today);
                if (rawPrice != null) {
                    BigDecimal fx = marketService.fxRateForCode(i.getCode());
                    if (fx != null) {
                        s.setTotalValue(rawPrice.multiply(BigDecimal.valueOf(i.getShares()))
                                .multiply(fx).setScale(2, RoundingMode.HALF_UP));
                        s.setPrice(rawPrice);
                    }
                }
                if (s.getPrice() == null) {
                    s.setPrice(s.getTotalValue().divide(BigDecimal.valueOf(i.getShares()), 4, RoundingMode.HALF_UP));
                }
            }
            snapshotMapper.upsert(s);
        }
    }

    /**
     * 今日快照市值：缓存有最新行情按 价×份额×汇率 算；无缓存用最近一个早于今日的
     * 历史快照价（与走势曲线同源，保证曲线末端连续）；都没有返回 null 退回原值。
     * 与 applyRealtime / recordDailyClose 同一口径（港股折人民币）。
     */
    private BigDecimal resolveTodayValue(Long userId, Investment i, LocalDate today) {
        BigDecimal rawPrice = latestRawPrice(userId, i, today);
        if (rawPrice == null) {
            return null;
        }
        BigDecimal fx = marketService.fxRateForCode(i.getCode());
        if (fx == null) {
            return null;
        }
        return rawPrice.multiply(BigDecimal.valueOf(i.getShares()))
                .multiply(fx).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 最新原始单价（标的币种，不折汇率）：行情缓存 → 最近一个早于今日的历史快照价。
     * 仅限带行情代码的份额型标的；港股回填存港币原始价、收盘落库按原始价落 market_quote，
     * 本方法与之保持同一口径。无行情代码返回 null。
     */
    private BigDecimal latestRawPrice(Long userId, Investment i, LocalDate today) {
        if (!StringUtils.hasText(i.getCode())) {
            return null;
        }
        MarketQuote quote = marketService.getQuote(i.getCode());
        if (quote != null && quote.getPrice() != null) {
            return quote.getPrice();
        }
        List<DailySnapshot> snaps = snapshotMapper.listRecentPrices(
                userId, DailySnapshot.KIND_INVEST, String.valueOf(i.getId()), 10);
        for (DailySnapshot snap : snaps) {
            if (snap.getSnapshotDate() != null && snap.getSnapshotDate().isBefore(today)
                    && snap.getPrice() != null && snap.getPrice().compareTo(BigDecimal.ZERO) > 0) {
                return snap.getPrice();
            }
        }
        return null;
    }

    /** 删除/卖出时清除该标的的全部历史快照（折线图上整条移除而不是降到 0） */
    public void purgeHistory(Long userId, String kind, Long refId) {
        snapshotMapper.deleteByRef(userId, kind, refId);
    }

    /**
     * 在任意日期补记一条快照（如新增资产/投资指定了过去日期）：
     * 折线图按月取「不晚于月末的最新快照」，因此只需在变更日写一条，
     * 该日之后的月份会自动延续该值，之前的月份保持 0 或更早的快照值。
     */
    public void recordAt(Long userId, String kind, Long refId, String name, String type,
                         LocalDate date, BigDecimal value, BigDecimal price) {
        recordAt(userId, kind, refId, name, type, date, value, price, null);
    }

    /**
     * 带当日累计投入的版本（kind=INVEST 时传入投资 amount，盈亏曲线据此计算历史盈亏）。
     */
    public void recordAt(Long userId, String kind, Long refId, String name, String type,
                         LocalDate date, BigDecimal value, BigDecimal price, BigDecimal investAmount) {
        DailySnapshot s = new DailySnapshot();
        s.setUserId(userId);
        s.setSnapshotDate(date);
        s.setKind(kind);
        s.setRefId(refId);
        s.setName(name);
        s.setType(type);
        s.setTotalValue(value);
        s.setInvestAmount(investAmount);
        s.setPrice(price);
        snapshotMapper.upsert(s);
    }
}
