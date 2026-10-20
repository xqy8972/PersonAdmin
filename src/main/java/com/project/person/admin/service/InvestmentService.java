package com.project.person.admin.service;

import com.project.person.admin.common.BizException;
import com.project.person.admin.common.UserContext;
import com.project.person.admin.mapper.AssetMapper;
import com.project.person.admin.mapper.DailySnapshotMapper;
import com.project.person.admin.mapper.InvestmentMapper;
import com.project.person.admin.mapper.InvestmentTradeMapper;
import com.project.person.admin.mapper.InvestmentValueHistoryMapper;
import com.project.person.admin.model.Asset;
import com.project.person.admin.model.DailySnapshot;
import com.project.person.admin.model.Investment;
import com.project.person.admin.model.InvestmentBuy;
import com.project.person.admin.model.InvestmentSell;
import com.project.person.admin.model.InvestmentTrade;
import com.project.person.admin.model.InvestmentValueHistory;
import com.project.person.admin.model.MarketDailyQuote;
import com.project.person.admin.model.MarketQuote;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class InvestmentService {

    private static final String TYPE_A_SHARE = "A股";
    private static final String TYPE_HK_SHARE = "港股";
    private static final String TYPE_FUND = "基金";

    /** 份额制类型：A股/港股（股数×股价）与基金（份额×净值）市值都按 份额×单价 计算；其他类型按金额 */
    private static boolean isShareBased(String type) {
        return TYPE_A_SHARE.equals(type) || TYPE_HK_SHARE.equals(type) || TYPE_FUND.equals(type);
    }

    /** 港币计价标的（买入/卖出/重算按汇率折算人民币） */
    private static boolean isHkType(String type) {
        return TYPE_HK_SHARE.equals(type);
    }

    @Autowired
    private InvestmentMapper investmentMapper;

    @Autowired
    private InvestmentTradeMapper tradeMapper;

    @Autowired
    private com.project.person.admin.mapper.MarketDailyQuoteMapper dailyQuoteMapper;
    @Autowired
    private InvestmentValueHistoryMapper valueHistoryMapper;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private AssetSupport assetSupport;

    @Autowired
    private SnapshotService snapshotService;

    @Autowired
    private DailySnapshotMapper snapshotMapper;

    @Autowired
    private MarketService marketService;

    @Autowired
    private AssetFlowService assetFlowService;

    /**
     * 投资列表：按当前最新总市值从大到小排列；
     * 带行情代码的标的按缓存实时计算市值（不改数据库，收盘后由定时任务落库）。
     */
    public List<Investment> list() {
        Long userId = UserContext.getUserId();
        List<Investment> list = investmentMapper.list(userId);
        marketService.applyRealtime(list);
        fillDayProfit(userId, list);
        list.sort(Comparator.comparing(Investment::getCurrentValue,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return list;
    }

    /**
     * 填充「最新盈亏」（列表列）：最近两个<b>交易日</b>收盘价差 × 当前持有数量。
     * 交易日历以 market_quote 收盘表为准（只有交易日才落库，周末补齐的平推快照不会混入）；
     * 收盘记录不足两条（新标还没落过库）时退回按快照最近两条计算；金额型/无行情标的返回 null。
     */
    private void fillDayProfit(Long userId, List<Investment> list) {
        List<Investment> shareBasedList = new ArrayList<>();
        List<Long> refIds = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (Investment inv : list) {
            if (inv.getShares() == null || inv.getShares() <= 0) {
                continue;
            }
            shareBasedList.add(inv);
            refIds.add(inv.getId());
            if (StringUtils.hasText(inv.getCode())) {
                codes.add(inv.getCode().trim().toLowerCase());
            }
        }
        if (shareBasedList.isEmpty()) {
            return;
        }
        // 每个标的最近两个收盘价：按 code 分组（交易日历），收盘不足两条的标的之后退回快照口径
        Map<String, List<MarketDailyQuote>> closesByCode = new HashMap<>();
        if (!codes.isEmpty()) {
            // 代码均为归一化的小写字母数字，直接拼入 IN 子句
            String codeStr = "'" + String.join("','", codes) + "'";
            for (MarketDailyQuote q : dailyQuoteMapper.listRecentByCodes(codeStr, 2)) {
                closesByCode.computeIfAbsent(q.getCode() == null ? "" : q.getCode().toLowerCase(),
                        k -> new ArrayList<>()).add(q);
            }
        }
        Map<Long, List<DailySnapshot>> recent = new HashMap<>();
        StringBuilder idStr = new StringBuilder();
        for (Long refId : refIds) {
            if (idStr.length() > 0) {
                idStr.append(',');
            }
            idStr.append(refId);
        }
        for (DailySnapshot s : snapshotMapper.listRecentPrices(
                userId, DailySnapshot.KIND_INVEST, idStr.toString(), 10)) {
            recent.computeIfAbsent(s.getRefId(), k -> new ArrayList<>()).add(s);
        }
        for (Investment inv : shareBasedList) {
            BigDecimal dayProfit = null;
            // 价差是标的币种（港股为港币），乘份额后须再乘汇率折人民币；取不到汇率跳过
            BigDecimal fx = StringUtils.hasText(inv.getCode())
                    ? marketService.fxRateForCode(inv.getCode()) : BigDecimal.ONE;
            List<MarketDailyQuote> closes = StringUtils.hasText(inv.getCode())
                    ? closesByCode.get(inv.getCode().trim().toLowerCase()) : null;
            if (fx != null && closes != null && closes.size() >= 2) {
                // 最近两个交易日收盘价差（ closes 按日期倒序）
                dayProfit = closes.get(0).getPrice().subtract(closes.get(1).getPrice())
                        .multiply(BigDecimal.valueOf(inv.getShares()))
                        .multiply(fx)
                        .setScale(2, RoundingMode.HALF_UP);
            } else if (fx != null) {
                // 收盘记录不足（新标的未落过库）：按历史 K 线快照算——
                // 从最新价向前找第一个价格不同的点（跳过周末/节假日补齐的平推点），即前一交易日
                List<DailySnapshot> prices = recent.get(inv.getId());
                if (prices != null && !prices.isEmpty() && prices.get(0).getPrice() != null) {
                    BigDecimal last = prices.get(0).getPrice();
                    BigDecimal prev = null;
                    for (int i = 1; i < prices.size(); i++) {
                        BigDecimal p = prices.get(i).getPrice();
                        if (p != null && p.compareTo(last) != 0) {
                            prev = p;
                            break;
                        }
                    }
                    dayProfit = last.subtract(prev == null ? last : prev)
                            .multiply(BigDecimal.valueOf(inv.getShares()))
                            .multiply(fx)
                            .setScale(2, RoundingMode.HALF_UP);
                }
            }
            inv.setDayProfit(dayProfit);
        }
    }

    /**
     * 新增投资：从出资资产扣款（资产 -= 累计投入金额），不产生支出记录。
     * 股票/基金必填买入数量与买入单价：股票=股数×股价（港股=港币，按汇率折人民币）、
     * 基金=份额×净值（人民币不折算）；投入金额自动折算成人民币。
     * 出资资产选填：不选则不扣款（存续投资）。
     * 买入日期早于今天时，在投资与出资资产两边都按买入日补记快照，折线图从买入月开始变化。
     */
    @Transactional
    public Investment create(Investment investment) {
        checkBasic(investment);
        checkCode(investment);
        Long userId = UserContext.getUserId();
        // 出资资产选填：不选则不从任何资产扣款（用于录入系统启用前的存续投资）
        Asset asset = investment.getSourceAssetId() == null ? null
                : assetSupport.requireCashAsset(userId, investment.getSourceAssetId());

        BigDecimal tradeFee = null;
        if (isShareBased(investment.getType())) {
            if (investment.getShares() == null || investment.getShares() <= 0) {
                throw BizException.badRequest(TYPE_FUND.equals(investment.getType()) ? "请填写买入份额" : "请填写买入股数");
            }
            BigDecimal price = investment.getPrice();
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                throw BizException.badRequest(TYPE_FUND.equals(investment.getType()) ? "请填写买入净值" : "请填写买入股价");
            }
            // 基金/A股为人民币计价不折算；仅港股按港币汇率折算
            BigDecimal fx = (isHkType(investment.getType()) && StringUtils.hasText(investment.getCode()))
                    ? requireFx(investment) : BigDecimal.ONE;
            // 投入金额 = 份额 × 买入单价 × (1 + 交易费率) + 固定手续费（基金=净值/人民币；股票=股价×汇率）→ 人民币
            BigDecimal feeDec = feeRateDecimal(investment);
            BigDecimal feeFix = feeFixedFor(investment, null);
            BigDecimal gross = price.multiply(BigDecimal.valueOf(investment.getShares())).multiply(fx);
            tradeFee = gross.multiply(feeDec).add(feeFix).setScale(2, RoundingMode.HALF_UP);
            BigDecimal total = gross.add(tradeFee).setScale(2, RoundingMode.HALF_UP);
            investment.setAmount(total);
            investment.setCurrentValue(total);
        } else {
            if (investment.getAmount() == null || investment.getAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw BizException.badRequest("累计投入金额不能为负");
            }
            if (investment.getCurrentValue() == null || investment.getCurrentValue().compareTo(BigDecimal.ZERO) < 0) {
                throw BizException.badRequest("当前市值不能为负");
            }
            investment.setShares(null);
        }

        if (asset != null && !investment.getBuyDate().isBefore(LocalDate.now())) {
            assetSupport.checkBalance(asset, investment.getAmount());
        }
        investment.setId(null);
        investment.setUserId(userId);
        investment.setRemark(investment.getRemark() == null ? "" : investment.getRemark());
        investmentMapper.insert(investment);
        // 交易记录：新增即一笔买入（K 线买卖标记与历史盈亏计算的数据源）
        InvestmentTrade buy = new InvestmentTrade();
        buy.setUserId(userId);
        buy.setInvestmentId(investment.getId());
        buy.setTradeType(InvestmentTrade.TYPE_BUY);
        buy.setTradeDate(investment.getBuyDate());
        buy.setShares(investment.getShares());
        buy.setPrice(isShareBased(investment.getType()) ? investment.getPrice() : null);
        buy.setAmount(investment.getAmount());
        buy.setFee(tradeFee);
        tradeMapper.insert(buy);
        if (asset != null) {
            assetMapper.adjustValue(asset.getId(), userId, investment.getAmount().negate());
            assetFlowService.record(userId, asset.getId(), AssetFlowService.TYPE_INVEST_BUY,
                    investment.getAmount().negate(), investment.getBuyDate(),
                    AssetFlowService.REF_INVESTMENT, investment.getId(), "新增投资：" + investment.getName());
        }
        // 买入日期早于今天：投资在买入日按当前市值补记（资产侧不在历史日期补快照——
        // 历史余额无法从当前余额反推，且历史买入不调整当前余额，见 buy/sell 同口径），
        // 之后的月份由「不晚于月末的最新快照」自动延续，折线图从买入月开始反映这笔投资
        LocalDate buyDate = investment.getBuyDate();
        if (buyDate.isBefore(LocalDate.now())) {
            BigDecimal priceAtBuy = null;
            if (investment.getShares() != null && investment.getShares() > 0
                    && investment.getCurrentValue() != null) {
                // 带码标的存标的币种原始价（港币股存港币），与回填/落库同口径
                BigDecimal fxb = StringUtils.hasText(investment.getCode())
                        ? marketService.fxRateForCode(investment.getCode()) : BigDecimal.ONE;
                priceAtBuy = fxb != null && fxb.compareTo(BigDecimal.ZERO) > 0
                        ? investment.getCurrentValue().divide(
                                BigDecimal.valueOf(investment.getShares()).multiply(fxb), 4, RoundingMode.HALF_UP)
                        : investment.getCurrentValue().divide(
                                BigDecimal.valueOf(investment.getShares()), 4, RoundingMode.HALF_UP);
            }
            snapshotService.recordAt(userId, DailySnapshot.KIND_INVEST, investment.getId(),
                    investment.getName(), investment.getType(), buyDate, investment.getCurrentValue(),
                    priceAtBuy, investment.getAmount());
        }
        snapshotService.recordToday(userId);
        // 带行情代码的股票：若走势图还没有历史，异步回填上市以来全部日 K
        marketService.backfillHistory(userId, investment);
        return investment;
    }

    /**
     * 修改投资：资产联动。
     * 股票/基金只能增减份额（可按新单价重估，买入扣资产、卖出回资产）；
     * 其他类型可修改金额（投入增加扣资产、减少回资产）。均不产生支出记录。
     */
    @Transactional
    public Investment update(Long id, Investment investment) {
        Long userId = UserContext.getUserId();
        Investment old = investmentMapper.findById(id, userId);
        if (old == null) {
            throw BizException.badRequest("标的不存在或不属于当前用户");
        }
        checkNotChannelSynced(old);
        checkBasic(investment);
        // 只有改了行情代码才重新调三方接口校验（没改不校验，避免离线时无法编辑）
        boolean codeChanged = old.getCode() == null
                ? StringUtils.hasText(investment.getCode())
                : !old.getCode().equalsIgnoreCase(investment.getCode() == null ? "" : investment.getCode().trim());
        if (codeChanged) {
            checkCode(investment);
        } else {
            investment.setCode(old.getCode());
        }

        // 出资资产：>0 为挂接该资产；null/0 表示不挂接（存续投资），编辑时已挂接的会撤下并退回投入
        Long newAssetId = (investment.getSourceAssetId() != null && investment.getSourceAssetId() > 0)
                ? investment.getSourceAssetId() : null;
        // 挂接关系变化：换资产、存续投资补挂资产（旧为空新非空）、撤下资产（旧非空新为空）
        boolean assetChanged = (old.getSourceAssetId() == null) != (newAssetId == null)
                || (old.getSourceAssetId() != null && !old.getSourceAssetId().equals(newAssetId));

        BigDecimal oldAmount = old.getAmount() == null ? BigDecimal.ZERO : old.getAmount();
        BigDecimal oldValue = old.getCurrentValue() == null ? BigDecimal.ZERO : old.getCurrentValue();
        // 编辑产生的资产变动流水（ref=investment_edit）先回滚，下面按新状态补记
        assetFlowService.rollback(userId, AssetFlowService.REF_INVEST_EDIT, id);
        // 份额/金额变化量（非零时记一笔加仓/减仓交易）
        Integer tradeSharesDelta = null;
        BigDecimal tradeAmountDelta = null;
        BigDecimal tradePrice = null;

        if (isShareBased(investment.getType())) {
            if (investment.getShares() == null || investment.getShares() < 0) {
                throw BizException.badRequest(TYPE_FUND.equals(investment.getType()) ? "请填写正确的持有份额" : "请填写正确的持股数量");
            }
            int oldShares = old.getShares() == null ? 0 : old.getShares();
            // 每份单价：前端传了单价用新单价，否则按原市值/原份额折算
            BigDecimal unitPrice;
            if (investment.getPrice() != null && investment.getPrice().compareTo(BigDecimal.ZERO) > 0) {
                unitPrice = investment.getPrice();
            } else {
                unitPrice = oldShares > 0
                        ? oldValue.divide(BigDecimal.valueOf(oldShares), 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
            }
            // 港股股票：显式传入的单价是港币，需乘汇率折算成人民币；基金与 A 股均为人民币不折算
            // 未传单价时按原市值/原份额折算，原市值已是人民币，不再乘汇率
            BigDecimal fx = (isHkType(investment.getType())
                    && investment.getPrice() != null && investment.getPrice().compareTo(BigDecimal.ZERO) > 0)
                    ? requireFx(investment) : BigDecimal.ONE;
            BigDecimal newValue = unitPrice.multiply(BigDecimal.valueOf(investment.getShares()))
                    .multiply(fx)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal delta = newValue.subtract(oldValue);
            BigDecimal newAmount = oldAmount.add(delta);

            if (assetChanged) {
                // 撤下或换出旧资产：旧投入退回（存续投资原本未扣款，旧资产为空则跳过）
                if (old.getSourceAssetId() != null) {
                    assetMapper.adjustValue(old.getSourceAssetId(), userId, oldAmount);
                    assetFlowService.record(userId, old.getSourceAssetId(), AssetFlowService.TYPE_INVEST_EDIT,
                            oldAmount, LocalDate.now(), AssetFlowService.REF_INVEST_EDIT, id, "撤下投资退回：" + old.getName());
                }
                // 挂入新资产：按新的累计投入全额扣款（存续投资补挂时把历史投入一次性补上）
                if (newAssetId != null) {
                    Asset newAsset = assetSupport.requireCashAsset(userId, newAssetId);
                    assetSupport.checkBalance(newAsset, newAmount);
                    assetMapper.adjustValue(newAsset.getId(), userId, newAmount.negate());
                    assetFlowService.record(userId, newAssetId, AssetFlowService.TYPE_INVEST_EDIT,
                            newAmount.negate(), LocalDate.now(), AssetFlowService.REF_INVEST_EDIT, id, "挂入投资扣款：" + investment.getName());
                }
            } else if (newAssetId != null) {
                if (delta.compareTo(BigDecimal.ZERO) > 0) {
                    Asset asset = assetSupport.requireCashAsset(userId, newAssetId);
                    assetSupport.checkBalance(asset, delta);
                    assetMapper.adjustValue(asset.getId(), userId, delta.negate());
                } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
                    assetMapper.adjustValue(newAssetId, userId, delta.negate());
                }
                if (delta.compareTo(BigDecimal.ZERO) != 0) {
                    assetFlowService.record(userId, newAssetId, AssetFlowService.TYPE_INVEST_EDIT,
                            delta.negate(), LocalDate.now(), AssetFlowService.REF_INVEST_EDIT, id, "投资调整：" + investment.getName());
                }
            }
            investment.setAmount(newAmount);
            investment.setCurrentValue(newValue);
            tradeSharesDelta = investment.getShares() - oldShares;
            tradeAmountDelta = newAmount.subtract(oldAmount);
            tradePrice = unitPrice;
        } else {
            BigDecimal newAmount = investment.getAmount() == null ? oldAmount : investment.getAmount();
            if (newAmount.compareTo(BigDecimal.ZERO) < 0) {
                throw BizException.badRequest("累计投入金额不能为负");
            }
            investment.setShares(null);
            investment.setAmount(newAmount);
            if (investment.getCurrentValue() == null) {
                investment.setCurrentValue(old.getCurrentValue());
            }
            BigDecimal delta = newAmount.subtract(oldAmount);
            if (assetChanged) {
                // 撤下或换出旧资产：旧投入退回（存续投资原本未扣款，旧资产为空则跳过）
                if (old.getSourceAssetId() != null) {
                    assetMapper.adjustValue(old.getSourceAssetId(), userId, oldAmount);
                    assetFlowService.record(userId, old.getSourceAssetId(), AssetFlowService.TYPE_INVEST_EDIT,
                            oldAmount, LocalDate.now(), AssetFlowService.REF_INVEST_EDIT, id, "撤下投资退回：" + old.getName());
                }
                // 挂入新资产：按新的累计投入全额扣款（存续投资补挂时把历史投入一次性补上）
                if (newAssetId != null) {
                    Asset newAsset = assetSupport.requireCashAsset(userId, newAssetId);
                    assetSupport.checkBalance(newAsset, newAmount);
                    assetMapper.adjustValue(newAsset.getId(), userId, newAmount.negate());
                    assetFlowService.record(userId, newAssetId, AssetFlowService.TYPE_INVEST_EDIT,
                            newAmount.negate(), LocalDate.now(), AssetFlowService.REF_INVEST_EDIT, id, "挂入投资扣款：" + investment.getName());
                }
            } else if (newAssetId != null) {
                if (delta.compareTo(BigDecimal.ZERO) > 0) {
                    Asset asset = assetSupport.requireCashAsset(userId, newAssetId);
                    assetSupport.checkBalance(asset, delta);
                    assetMapper.adjustValue(asset.getId(), userId, delta.negate());
                } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
                    assetMapper.adjustValue(newAssetId, userId, delta.negate());
                }
                if (delta.compareTo(BigDecimal.ZERO) != 0) {
                    assetFlowService.record(userId, newAssetId, AssetFlowService.TYPE_INVEST_EDIT,
                            delta.negate(), LocalDate.now(), AssetFlowService.REF_INVEST_EDIT, id, "投资调整：" + investment.getName());
                }
            }
            tradeAmountDelta = newAmount.subtract(oldAmount);
        }
        investment.setId(id);
        investment.setUserId(userId);
        investment.setSourceAssetId(newAssetId);
        // 交易费率/固定手续费未传时保留原值（编辑不要求重填）
        if (investment.getFeeRate() == null) {
            investment.setFeeRate(old.getFeeRate());
        }
        if (investment.getFeeFixed() == null) {
            investment.setFeeFixed(old.getFeeFixed());
        }
        investment.setRemark(investment.getRemark() == null ? "" : investment.getRemark());
        if (investmentMapper.update(investment) == 0) {
            throw BizException.badRequest("标的不存在或不属于当前用户");
        }
        recordDeltaTrade(userId, id, old.getType(), tradeSharesDelta, tradeAmountDelta, tradePrice);
        // 台账已记 delta 交易：amount 以台账净额为准重新同步（渠道标的在 sync 内自动跳过）
        syncAmountFromTrades(userId, id, old.getSourceChannel());
        investment.setAmount(tradeMapper.netAmount(id, userId).setScale(2, RoundingMode.HALF_UP));
        // 买入日期被改到过去：在新买入日回写一条投资快照，历史折线同步生效
        LocalDate buyDate = investment.getBuyDate();
        if (buyDate.isBefore(LocalDate.now())
                && (old.getBuyDate() == null || !buyDate.equals(old.getBuyDate()))) {
            BigDecimal priceAtBuy = null;
            if (investment.getShares() != null && investment.getShares() > 0
                    && investment.getCurrentValue() != null) {
                // 带码标的存标的币种原始价（港币股存港币），与回填/落库同口径
                BigDecimal fxb = StringUtils.hasText(investment.getCode())
                        ? marketService.fxRateForCode(investment.getCode()) : BigDecimal.ONE;
                priceAtBuy = fxb != null && fxb.compareTo(BigDecimal.ZERO) > 0
                        ? investment.getCurrentValue().divide(
                                BigDecimal.valueOf(investment.getShares()).multiply(fxb), 4, RoundingMode.HALF_UP)
                        : investment.getCurrentValue().divide(
                                BigDecimal.valueOf(investment.getShares()), 4, RoundingMode.HALF_UP);
            }
            snapshotService.recordAt(userId, DailySnapshot.KIND_INVEST, id,
                    investment.getName(), investment.getType(), buyDate, investment.getCurrentValue(),
                    priceAtBuy, investment.getAmount());
        }
        snapshotService.recordToday(userId);
        return investment;
    }

    /** 删除标的：累计投入金额退回出资资产，与该标的相关的全部历史数据（市值快照、每日快照、交易记录）一并清除。渠道同步标的也可删除（下次渠道推送会按持仓重新创建） */
    @Transactional
    public void delete(Long id) {
        Long userId = UserContext.getUserId();
        Investment old = investmentMapper.findById(id, userId);
        if (old == null) {
            throw BizException.badRequest("标的不存在或不属于当前用户");
        }
        if (old.getSourceAssetId() != null && old.getAmount() != null) {
            assetMapper.adjustValue(old.getSourceAssetId(), userId, old.getAmount());
            // 新增时的扣款流水（ref=investment）回滚，改记一笔删除退回流水
            assetFlowService.rollback(userId, AssetFlowService.REF_INVESTMENT, id);
            assetFlowService.record(userId, old.getSourceAssetId(), AssetFlowService.TYPE_INVEST_DELETE,
                    old.getAmount(), LocalDate.now(), AssetFlowService.REF_INVESTMENT, id, "删除投资退回：" + old.getName());
        }
        investmentMapper.delete(id, userId);
        valueHistoryMapper.deleteByInvestmentId(id, userId);
        tradeMapper.deleteByInvestmentId(id, userId);
        snapshotService.purgeHistory(userId, DailySnapshot.KIND_INVEST, id);
        snapshotService.recordToday(userId);
    }

    /**
     * 编辑导致的份额/金额变化记一笔交易：加仓（份额或金额增加）记 BUY，减仓记 SELL。
     * 份额制标的份额没变（仅按新单价重估）不记交易，避免把估值调整当成买卖。
     */
    private void recordDeltaTrade(Long userId, Long investmentId, String type,
                                  Integer sharesDelta, BigDecimal amountDelta, BigDecimal price) {
        if (amountDelta == null || amountDelta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        if (sharesDelta != null && sharesDelta == 0) {
            return;
        }
        boolean buy = sharesDelta == null ? amountDelta.compareTo(BigDecimal.ZERO) > 0 : sharesDelta > 0;
        InvestmentTrade trade = new InvestmentTrade();
        trade.setUserId(userId);
        trade.setInvestmentId(investmentId);
        trade.setTradeType(buy ? InvestmentTrade.TYPE_BUY : InvestmentTrade.TYPE_SELL);
        trade.setTradeDate(LocalDate.now());
        trade.setShares(sharesDelta == null ? null : Math.abs(sharesDelta));
        trade.setPrice(isShareBased(type) ? price : null);
        trade.setAmount(amountDelta.abs().setScale(2, RoundingMode.HALF_UP));
        tradeMapper.insert(trade);
    }

    /**
     * 台账净额同步 investment.amount：investment_trade 是 amount 的唯一权威来源
     * （摊薄口径：累计买入 − 累计卖出，与同花顺一致）。渠道同步标的的 amount 由
     * 渠道侧维护且无台账，绝不能用净额覆盖，必须跳过。
     */
    private void syncAmountFromTrades(Long userId, Long investmentId, String sourceChannel) {
        if (StringUtils.hasText(sourceChannel)) {
            return;
        }
        investmentMapper.updateAmount(investmentId, userId,
                tradeMapper.netAmount(investmentId, userId).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * 记录一笔金额型台账（支出-投资联动用）：delta&gt;0 记 BUY，delta&lt;0 记 SELL。
     * 金额型交易无股数/单价（shares/price 传 null），记完同步 investment.amount。
     */
    @Transactional
    public void recordAmountTrade(Long userId, Long investmentId, BigDecimal deltaAmount, LocalDate tradeDate) {
        if (deltaAmount == null || deltaAmount.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        Investment inv = investmentMapper.findById(investmentId, userId);
        if (inv == null) {
            throw BizException.badRequest("标的不存在或不属于当前用户");
        }
        InvestmentTrade trade = new InvestmentTrade();
        trade.setUserId(userId);
        trade.setInvestmentId(investmentId);
        trade.setTradeType(deltaAmount.compareTo(BigDecimal.ZERO) > 0
                ? InvestmentTrade.TYPE_BUY : InvestmentTrade.TYPE_SELL);
        trade.setTradeDate(tradeDate != null ? tradeDate : LocalDate.now());
        trade.setShares(null);
        trade.setPrice(null);
        trade.setAmount(deltaAmount.abs().setScale(2, RoundingMode.HALF_UP));
        tradeMapper.insert(trade);
        syncAmountFromTrades(userId, investmentId, inv.getSourceChannel());
    }

    /**
     * 卖出投资：卖出所得回到回款资产（现金/银行卡），不产生收入记录。
     * 股票/基金按「卖出份额 + 卖出单价」（单价留空按当前市值/份额折算，基金净值为人民币）；
     * 其他类型按「卖出金额」。全额卖出不清仓：行保留（份额/市值/投入清零）+ 历史保留。
     * tradeDate 可指定过去日期补录历史卖出，交易记录与快照按该日期落。
     */
    @Transactional
    public void sell(Long id, InvestmentSell sell) {
        Long userId = UserContext.getUserId();
        if (sell == null) {
            throw BizException.badRequest("卖出参数不能为空");
        }
        Investment old = investmentMapper.findById(id, userId);
        if (old == null) {
            throw BizException.badRequest("标的不存在或不属于当前用户");
        }
        checkNotChannelSynced(old);
        // 回款资产选填：不填则卖出所得不回到任何资产（外部资金），只记台账
        Asset asset = sell.getAssetId() == null ? null
                : assetSupport.requireCashAsset(userId, sell.getAssetId());
        LocalDate tradeDate = sell.getTradeDate() != null ? sell.getTradeDate() : LocalDate.now();
        if (tradeDate.isAfter(LocalDate.now())) {
            throw BizException.badRequest("卖出日期不能晚于今天");
        }

        BigDecimal oldValue = old.getCurrentValue() == null ? BigDecimal.ZERO : old.getCurrentValue();

        BigDecimal proceeds;
        BigDecimal tradeFee;
        BigDecimal newValue;
        Integer newShares = null;
        if (isShareBased(old.getType())) {
            int oldShares = old.getShares() == null ? 0 : old.getShares();
            if (sell.getShares() == null || sell.getShares() <= 0) {
                throw BizException.badRequest(TYPE_FUND.equals(old.getType()) ? "请填写卖出份额" : "请填写卖出股数");
            }
            if (sell.getShares() > oldShares) {
                throw BizException.badRequest((TYPE_FUND.equals(old.getType()) ? "卖出份额不能超过当前持有份额（当前 " : "卖出股数不能超过当前持股数（当前 ")
                        + oldShares + "）");
            }
            BigDecimal unitPrice;
            if (sell.getPrice() != null && sell.getPrice().compareTo(BigDecimal.ZERO) > 0) {
                unitPrice = sell.getPrice();
            } else {
                unitPrice = oldShares > 0
                        ? oldValue.divide(BigDecimal.valueOf(oldShares), 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
            }
            // 显式传入的卖出单价是标的币种（港股为港币），需乘汇率折算；基金/A 股为人民币不折算；未传则按人民币市值折算
            BigDecimal fx = (isHkType(old.getType())
                    && sell.getPrice() != null && sell.getPrice().compareTo(BigDecimal.ZERO) > 0)
                    ? requireFxAt(old, tradeDate) : BigDecimal.ONE;
            // 成交总额直填（照同花顺含费净回款）：填了以它为准，不再按公式算
            if (sell.getTotal() != null && sell.getTotal().compareTo(BigDecimal.ZERO) > 0) {
                proceeds = sell.getTotal().setScale(2, RoundingMode.HALF_UP);
                // 净回款直填时费用无法从公式反推，记录显式传入的单笔手续费（未传记 0）
                tradeFee = sell.getFee() != null ? sell.getFee().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
            } else {
                // 卖出净所得 = 单价 × 份额 × 汇率 − 手续费（费从回款中扣除）
                BigDecimal feeDec = feeRateDecimal(old);
                BigDecimal feeFix = feeFixedFor(old, sell.getFee());
                BigDecimal gross = unitPrice.multiply(BigDecimal.valueOf(sell.getShares())).multiply(fx);
                tradeFee = gross.multiply(feeDec).add(feeFix).setScale(2, RoundingMode.HALF_UP);
                proceeds = gross.subtract(tradeFee).setScale(2, RoundingMode.HALF_UP);
            }
            if (proceeds.compareTo(BigDecimal.ZERO) < 0) {
                throw BizException.badRequest("手续费超过卖出所得，请检查费率和固定手续费");
            }
            newShares = oldShares - sell.getShares();
            newValue = unitPrice.multiply(BigDecimal.valueOf(newShares))
                    .multiply(fx)
                    .setScale(2, RoundingMode.HALF_UP);
            // 投入金额不再按比例结转：记完台账后统一按净额（Σ买−Σ卖）重算，见下方
        } else {
            if (sell.getAmount() == null || sell.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw BizException.badRequest("请填写卖出金额");
            }
            if (sell.getAmount().compareTo(oldValue) > 0) {
                throw BizException.badRequest("卖出金额不能超过当前市值（当前 ¥" + oldValue + "）");
            }
            proceeds = sell.getAmount();
            // 金额型卖出不按公式收手续费，记录显式传入的单笔手续费（未传记 0）
            tradeFee = sell.getFee() != null ? sell.getFee().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
            newValue = oldValue.subtract(proceeds);
        }

        // 选了回款资产即入账（不限当天）：流水按交易日期落账；不选则所得不回到任何资产
        if (asset != null) {
            assetMapper.adjustValue(asset.getId(), userId, proceeds);
        }
        // 交易记录：每次卖出记一笔 SELL（K 线卖出标记与历史盈亏计算的数据源）
        InvestmentTrade sellTrade = new InvestmentTrade();
        sellTrade.setUserId(userId);
        sellTrade.setInvestmentId(id);
        sellTrade.setTradeType(InvestmentTrade.TYPE_SELL);
        sellTrade.setTradeDate(tradeDate);
        sellTrade.setShares(isShareBased(old.getType()) ? sell.getShares() : null);
        sellTrade.setPrice(isShareBased(old.getType())
                ? (sell.getPrice() != null && sell.getPrice().compareTo(BigDecimal.ZERO) > 0
                        ? sell.getPrice() : (old.getShares() != null && old.getShares() > 0
                                ? oldValue.divide(BigDecimal.valueOf(old.getShares()), 4, RoundingMode.HALF_UP)
                                : null))
                : null);
        sellTrade.setAmount(proceeds);
        sellTrade.setFee(tradeFee);
        sellTrade.setTargetAssetId(asset != null ? asset.getId() : null);
        tradeMapper.insert(sellTrade);
        if (asset != null) {
            assetFlowService.record(userId, asset.getId(), AssetFlowService.TYPE_INVEST_SELL,
                    proceeds, tradeDate, AssetFlowService.REF_INVEST_TRADE, sellTrade.getId(),
                    "卖出回款：" + old.getName());
        }

        // 投入金额 = 台账净额（Σ买 − Σ卖，摊薄口径，与同花顺一致）：
        // 清仓后也不一定为 0（累计卖出超过买入时净额为负，同花顺即如此展示）
        BigDecimal netAmount = tradeMapper.netAmount(id, userId).setScale(2, RoundingMode.HALF_UP);

        boolean fullySold = BigDecimal.ZERO.compareTo(newValue) == 0
                || (newShares != null && newShares == 0);
        if (fullySold) {
            // 清仓不删除标的：行保留（份额/市值清零，投入为台账净额），历史快照保留，
            // 总盈亏走势仍体现该标的的历史盈亏；后续重新买入在此行上累加
            Investment upd = new Investment();
            upd.setId(id);
            upd.setUserId(userId);
            upd.setName(old.getName());
            upd.setType(old.getType());
            upd.setCode(old.getCode());
            upd.setAmount(netAmount);
            upd.setCurrentValue(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            upd.setShares(isShareBased(old.getType()) ? 0 : null);
            upd.setSourceAssetId(old.getSourceAssetId());
            upd.setSourceChannel(old.getSourceChannel());
            upd.setFeeRate(old.getFeeRate());
            upd.setFeeFixed(old.getFeeFixed());
            upd.setBuyDate(old.getBuyDate());
            upd.setRemark(old.getRemark());
            investmentMapper.update(upd);
            recordSellSnapshot(userId, old, BigDecimal.ZERO, netAmount, 0, tradeDate);
            return;
        }

        Investment upd = new Investment();
        upd.setId(id);
        upd.setUserId(userId);
        upd.setName(old.getName());
        upd.setType(old.getType());
        upd.setCode(old.getCode());
        upd.setAmount(netAmount);
        upd.setCurrentValue(newValue);
        upd.setShares(newShares);
        upd.setSourceAssetId(old.getSourceAssetId());
        upd.setFeeRate(old.getFeeRate());
        upd.setFeeFixed(old.getFeeFixed());
        upd.setBuyDate(old.getBuyDate());
        upd.setRemark(old.getRemark());
        if (investmentMapper.update(upd) == 0) {
            throw BizException.badRequest("标的不存在或不属于当前用户");
        }
        recordSellSnapshot(userId, old, newValue, netAmount, newShares, tradeDate);
    }

    /**
     * 买入（加仓）投资：股票/基金按「买入股数/份额 + 买入单价」，可选从出资资产（现金/银行卡）扣款。
     * 买入时间默认今天；填过去日期用于补录历史买入（交易记录与快照按该日期落，台账重算历史曲线）。
     * 投入金额 = 单价 × 数量 × 汇率（港股港币折人民币；基金/A 股人民币不折算）。
     * 清仓后重新买入在同一行上累加份额与成本。
     */
    @Transactional
    public void buy(Long id, InvestmentBuy buy) {
        Long userId = UserContext.getUserId();
        if (buy == null) {
            throw BizException.badRequest("买入参数不能为空");
        }
        Investment old = investmentMapper.findById(id, userId);
        if (old == null) {
            throw BizException.badRequest("标的不存在或不属于当前用户");
        }
        checkNotChannelSynced(old);
        if (!isShareBased(old.getType())) {
            throw BizException.badRequest("仅股票/基金支持买入加仓，其他类型请用编辑调整金额");
        }
        if (buy.getShares() == null || buy.getShares() <= 0) {
            throw BizException.badRequest(TYPE_FUND.equals(old.getType()) ? "请填写买入份额" : "请填写买入股数");
        }
        if (buy.getPrice() == null || buy.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.badRequest(TYPE_FUND.equals(old.getType()) ? "请填写买入净值" : "请填写买入股价");
        }
        LocalDate tradeDate = buy.getTradeDate() != null ? buy.getTradeDate() : LocalDate.now();
        if (tradeDate.isAfter(LocalDate.now())) {
            throw BizException.badRequest("买入日期不能晚于今天");
        }
        // 港股股票：显式单价为港币，需乘汇率折人民币；基金/A 股人民币不折算
        BigDecimal fx = isHkType(old.getType()) && StringUtils.hasText(old.getCode())
                ? requireFxAt(old, tradeDate) : BigDecimal.ONE;
        // 成交总额直填（照同花顺含费总扣款）：填了以它为准，不再按公式算
        final BigDecimal cost;
        final BigDecimal tradeFee;
        if (buy.getTotal() != null && buy.getTotal().compareTo(BigDecimal.ZERO) > 0) {
            cost = buy.getTotal().setScale(2, RoundingMode.HALF_UP);
            // 总额直填时费用无法从公式反推，记录显式传入的单笔手续费（未传记 0）
            tradeFee = buy.getFee() != null ? buy.getFee().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
        } else {
            // 买入成本 = 单价 × 数量 × 汇率 × (1 + 交易费率) + 固定手续费（费率在投资上维护，单笔可传 fee 覆盖）
            BigDecimal feeDec = feeRateDecimal(old);
            BigDecimal feeFix = feeFixedFor(old, buy.getFee());
            BigDecimal gross = buy.getPrice().multiply(BigDecimal.valueOf(buy.getShares())).multiply(fx);
            tradeFee = gross.multiply(feeDec).add(feeFix).setScale(2, RoundingMode.HALF_UP);
            cost = gross.add(tradeFee).setScale(2, RoundingMode.HALF_UP);
        }

        Asset asset = buy.getAssetId() == null ? null
                : assetSupport.requireCashAsset(userId, buy.getAssetId());
        // 选了出资资产即扣款（不限当天）：流水按交易日期落账，历史补录同样从当前余额扣出并记录
        if (asset != null) {
            assetSupport.checkBalance(asset, cost);
            assetMapper.adjustValue(asset.getId(), userId, cost.negate());
        }

        int oldShares = old.getShares() == null ? 0 : old.getShares();
        int newShares = oldShares + buy.getShares();
        // 加仓后市值按最新行情价计算（与列表实时口径一致），无行情时退回按买入价
        BigDecimal marketPrice = null;
        if (StringUtils.hasText(old.getCode())) {
            MarketQuote quote = marketService.getQuote(old.getCode());
            if (quote != null && quote.getPrice() != null) {
                marketPrice = quote.getPrice();
            }
        }
        BigDecimal unitValue = (marketPrice != null ? marketPrice : buy.getPrice());
        BigDecimal newValue = unitValue.multiply(BigDecimal.valueOf(newShares))
                .multiply(fx).setScale(2, RoundingMode.HALF_UP);

        InvestmentTrade buyTrade = new InvestmentTrade();
        buyTrade.setUserId(userId);
        buyTrade.setInvestmentId(id);
        buyTrade.setTradeType(InvestmentTrade.TYPE_BUY);
        buyTrade.setTradeDate(tradeDate);
        buyTrade.setShares(buy.getShares());
        buyTrade.setPrice(buy.getPrice());
        buyTrade.setAmount(cost);
        buyTrade.setFee(tradeFee);
        buyTrade.setRemark(buy.getRemark() == null ? "" : buy.getRemark());
        tradeMapper.insert(buyTrade);
        if (asset != null) {
            assetFlowService.record(userId, asset.getId(), AssetFlowService.TYPE_INVEST_BUY,
                    cost.negate(), tradeDate, AssetFlowService.REF_INVEST_TRADE, buyTrade.getId(),
                    "买入加仓：" + old.getName());
        }

        // 投入金额 = 台账净额（Σ买 − Σ卖，摊薄口径），新增买入后同步
        BigDecimal netAmount = tradeMapper.netAmount(id, userId).setScale(2, RoundingMode.HALF_UP);

        Investment upd = new Investment();
        upd.setId(id);
        upd.setUserId(userId);
        upd.setName(old.getName());
        upd.setType(old.getType());
        upd.setCode(old.getCode());
        upd.setAmount(netAmount);
        upd.setCurrentValue(newValue);
        upd.setShares(newShares);
        upd.setSourceAssetId(old.getSourceAssetId());
        upd.setBuyDate(old.getBuyDate() == null ? tradeDate : old.getBuyDate());
        upd.setSourceChannel(old.getSourceChannel());
        upd.setFeeRate(old.getFeeRate());
        upd.setFeeFixed(old.getFeeFixed());
        upd.setRemark(old.getRemark());
        if (investmentMapper.update(upd) == 0) {
            throw BizException.badRequest("标的不存在或不属于当前用户");
        }

        if (tradeDate.isBefore(LocalDate.now())) {
            // 补录历史买入：按交易台账把首笔交易以来到今日的逐日快照重算，
            // 买入日之后的点反映加仓后的持有数量，曲线在历史买入日同步抬升
            rewriteSnapshotsFromTrades(userId, upd);
        }
        snapshotService.recordToday(userId);
    }

    /**
     * 卖出后的快照落库：卖出日期为今天时记今日快照；为过去日期时（补录历史卖出）按卖出日回写
     * 投资侧在卖出日回写快照，折线图在卖出日反映变化；资产侧不在历史日期补快照
     * （历史余额无法从当前余额反推，且补录的历史买卖不调整当前余额）。
     */
    private void recordSellSnapshot(Long userId, Investment old,
                                    BigDecimal newValue, BigDecimal newAmount,
                                    Integer newShares, LocalDate tradeDate) {
        if (!tradeDate.isBefore(LocalDate.now())) {
            snapshotService.recordToday(userId);
            return;
        }
        // 卖出日的快照价：部分卖出用卖后单价；全额卖出（单价无意义）保留卖出前市价，
        // 避免把该日价格置空导致台账重算跳点。单价存标的币种原始价（与回填/落库同口径），
        // 由人民币市值反推：原始价 = 人民币市值 ÷ (份额 × 汇率)
        BigDecimal oldSharesNum = old.getShares() == null ? BigDecimal.ZERO : BigDecimal.valueOf(old.getShares());
        int oldShares = old.getShares() == null ? 0 : old.getShares();
        BigDecimal fxRaw = StringUtils.hasText(old.getCode())
                ? marketService.fxRateForCodeAt(old.getCode(), tradeDate) : BigDecimal.ONE;
        BigDecimal priceAfter;
        if (newShares != null && newShares > 0 && newValue != null) {
            priceAfter = fxRaw != null && fxRaw.compareTo(BigDecimal.ZERO) > 0
                    ? newValue.divide(BigDecimal.valueOf(newShares).multiply(fxRaw), 4, RoundingMode.HALF_UP)
                    : newValue.divide(BigDecimal.valueOf(newShares), 4, RoundingMode.HALF_UP);
        } else {
            priceAfter = oldShares > 0 && old.getCurrentValue() != null
                    ? (fxRaw != null && fxRaw.compareTo(BigDecimal.ZERO) > 0
                            ? old.getCurrentValue().divide(oldSharesNum.multiply(fxRaw), 4, RoundingMode.HALF_UP)
                            : old.getCurrentValue().divide(oldSharesNum, 4, RoundingMode.HALF_UP))
                    : null;
        }
        snapshotService.recordAt(userId, DailySnapshot.KIND_INVEST, old.getId(),
                old.getName(), old.getType(), tradeDate, newValue, priceAfter, newAmount);
        // 资产侧不在历史日期补快照：历史余额无法从当前余额反推，硬写「当前余额±所得」
        // 会在资产走势的历史月份制造虚假跳变；补录的历史买卖本就不调整当前余额（见 sell）
        // 补录历史卖出后，按交易台账把买入日以来到今日的快照重算一遍，
        // 卖出日之后的点不再停留在卖出前市值（曲线不会在卖出日之后又弹回去）
        rewriteSnapshotsFromTrades(userId, old);
    }

    /**
     * 按交易台账重算某标的（股票/基金且有行情代码）从首笔交易到今日的逐日快照：
     * 份额与成本随台账逐日演进（平均成本法），市值 = 当日快照价 × 当日持有份额 × 汇率。
     * 用于补录历史卖出/买入后让整条曲线与台账一致；无价格的日子跳过（由 recordToday 补）。
     */
    /**
     * 全量按交易台账重算所有带行情代码的份额型标的历史快照（历史汇率回填后调用）：
     * 逐日演进份额与成本、逐日取当日汇率，让存量曲线与历史汇率口径对齐。
     */
    public int rebuildSnapshotsFromTradesAll() {
        int rebuilt = 0;
        for (Investment inv : investmentMapper.listAll()) {
            try {
                if (StringUtils.hasText(inv.getCode()) && isShareBased(inv.getType())) {
                    rewriteSnapshotsFromTrades(inv.getUserId(), inv);
                    rebuilt++;
                }
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(InvestmentService.class)
                        .warn("投资 [{}] 台账重算失败: {}", inv.getId(), e.getMessage());
            }
        }
        return rebuilt;
    }

    private void rewriteSnapshotsFromTrades(Long userId, Investment inv) {
        if (inv == null || !StringUtils.hasText(inv.getCode()) || !isShareBased(inv.getType())) {
            return;
        }
        List<InvestmentTrade> trades = tradeMapper.listByInvestment(inv.getId(), userId);
        if (trades.isEmpty()) {
            return;
        }
        // 逐日市值按「当日汇率」折算：历史买卖发生日的汇率可能不同于现在
        List<com.project.person.admin.model.ExchangeRate> fxHistory =
                marketService.fxHistoryForCodeUpTo(inv.getCode(), LocalDate.now());
        BigDecimal fallbackFx = marketService.fxRateForCode(inv.getCode());
        if (fallbackFx == null) {
            return;
        }
        LocalDate from = trades.get(0).getTradeDate();
        LocalDate today = LocalDate.now();
        // 首笔交易之前的快照（回填按「当前份额」写了完整市值）不属于持仓期，清零，
        // 否则买入日之前的曲线虚高（如 2025-01 就出现未买入标的的市值）
        for (DailySnapshot p : snapshotMapper.listByRange(userId, DailySnapshot.KIND_INVEST,
                LocalDate.of(2000, 1, 1), from.minusDays(1))) {
            if (!p.getRefId().equals(inv.getId()) || p.getPrice() == null
                    || p.getTotalValue() == null || p.getTotalValue().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            snapshotService.recordAt(userId, DailySnapshot.KIND_INVEST, inv.getId(),
                    inv.getName(), inv.getType(), p.getSnapshotDate(),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), p.getPrice(), null);
        }
        int ti = 0;
        int shares = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (DailySnapshot p : snapshotMapper.listByRange(
                userId, DailySnapshot.KIND_INVEST, from, today)) {
            if (!p.getRefId().equals(inv.getId()) || p.getPrice() == null) {
                continue;
            }
            while (ti < trades.size() && !trades.get(ti).getTradeDate().isAfter(p.getSnapshotDate())) {
                InvestmentTrade t = trades.get(ti++);
                if (InvestmentTrade.TYPE_BUY.equals(t.getTradeType())) {
                    shares += t.getShares() == null ? 0 : t.getShares();
                    cost = cost.add(t.getAmount() == null ? BigDecimal.ZERO : t.getAmount());
                } else {
                    int sold = Math.min(t.getShares() == null ? 0 : t.getShares(), shares);
                    if (shares > 0 && sold > 0) {
                        cost = cost.subtract(cost.multiply(BigDecimal.valueOf(sold))
                                .divide(BigDecimal.valueOf(shares), 2, RoundingMode.HALF_UP));
                    }
                    shares -= sold;
                }
            }
            BigDecimal fx = fxForDay(fxHistory, p.getSnapshotDate(), fallbackFx);
            BigDecimal value = p.getPrice().multiply(BigDecimal.valueOf(shares)).multiply(fx)
                    .setScale(2, RoundingMode.HALF_UP);
            snapshotService.recordAt(userId, DailySnapshot.KIND_INVEST, inv.getId(),
                    inv.getName(), inv.getType(), p.getSnapshotDate(), value, p.getPrice(), cost);
        }
    }

    /** 某日适用的汇率：历史中不晚于该日的最后一条；该日之前无记录退回最新缓存汇率 */
    private BigDecimal fxForDay(List<com.project.person.admin.model.ExchangeRate> historyAsc,
                                LocalDate day, BigDecimal fallback) {
        BigDecimal fx = fallback;
        for (com.project.person.admin.model.ExchangeRate er : historyAsc) {
            if (er.getRateDate() == null || er.getRate() == null) {
                continue;
            }
            if (!er.getRateDate().isAfter(day)) {
                fx = er.getRate();
            } else {
                break;
            }
        }
        return fx;
    }

    /** 每日走势：读每日快照表（任何变动都会留痕，删除后曲线落到 0） */
    public List<Map<String, Object>> trend(Long id, String start, String end) {
        Long userId = UserContext.getUserId();
        LocalDate startDate = StringUtils.hasText(start) ? IncomeService.parseDate(start, "start") : LocalDate.now().minusDays(29);
        LocalDate endDate = StringUtils.hasText(end) ? IncomeService.parseDate(end, "end") : LocalDate.now();
        if (startDate.isAfter(endDate)) {
            throw BizException.badRequest("start 不能晚于 end");
        }
        List<DailySnapshot> snapshots = snapshotMapper.listByRange(userId, DailySnapshot.KIND_INVEST, startDate, endDate);
        List<Map<String, Object>> data = new ArrayList<>();
        for (DailySnapshot s : snapshots) {
            if (!s.getRefId().equals(id)) {
                continue;
            }
            Map<String, Object> point = new HashMap<>();
            point.put("date", s.getSnapshotDate());
            point.put("value", s.getTotalValue());
            point.put("price", s.getPrice());
            data.add(point);
        }
        return data;
    }

    /**
     * 投资盈亏走势：按天聚合投资快照的（总市值 − 台账累计净投入），摊薄口径（与同花顺一致）。
     * 每日净投入 = 截至当日的 Σ买入金额 − Σ卖出金额；清仓后市值归零，净投入为负，
     * 盈亏冻结在清仓时刻（不再随行情波动），卖出后重新买入在同一曲线上累加。
     * 新增/加仓/卖出/每日收盘落库都会留快照，曲线随操作与行情逐日变化。
     */
    public List<Map<String, Object>> profitTrend(String start, String end) {
        Long userId = UserContext.getUserId();
        LocalDate startDate = StringUtils.hasText(start) ? IncomeService.parseDate(start, "start") : LocalDate.now().minusDays(179);
        LocalDate endDate = StringUtils.hasText(end) ? IncomeService.parseDate(end, "end") : LocalDate.now();
        if (startDate.isAfter(endDate)) {
            throw BizException.badRequest("start 不能晚于 end");
        }
        // 每日净投入变动：BUY +amount、SELL −amount，按交易日聚合
        Map<LocalDate, BigDecimal> netByDay = new TreeMap<>();
        for (Investment inv : investmentMapper.list(userId)) {
            for (InvestmentTrade t : tradeMapper.listByInvestment(inv.getId(), userId)) {
                BigDecimal signed = (InvestmentTrade.TYPE_BUY.equals(t.getTradeType()) ? BigDecimal.ONE : BigDecimal.ONE.negate())
                        .multiply(t.getAmount() == null ? BigDecimal.ZERO : t.getAmount());
                netByDay.merge(t.getTradeDate(), signed, BigDecimal::add);
            }
        }
        List<Map<String, Object>> data = new ArrayList<>();
        List<DailySnapshot> days = snapshotMapper.sumInvestByDay(userId, startDate, endDate);
        BigDecimal cumNet = BigDecimal.ZERO;
        // 按快照日升序，把不晚于当日的净投入变动累加进来（交易可能落在无快照的日期）；
        // 用下标游标而非迭代器——next() 过的条目不能回退，迭代器跨循环续用会漏掉未消费条目
        List<Map.Entry<LocalDate, BigDecimal>> netEntries = new ArrayList<>(netByDay.entrySet());
        int ri = 0;
        for (DailySnapshot s : days) {
            while (ri < netEntries.size() && !netEntries.get(ri).getKey().isAfter(s.getSnapshotDate())) {
                cumNet = cumNet.add(netEntries.get(ri).getValue());
                ri++;
            }
            BigDecimal value = s.getTotalValue() == null ? BigDecimal.ZERO : s.getTotalValue();
            Map<String, Object> point = new HashMap<>();
            point.put("date", s.getSnapshotDate());
            point.put("profit", value.subtract(cumNet).setScale(2, RoundingMode.HALF_UP));
            data.add(point);
        }
        return data;
    }

    /**
     * 某标的的买卖交易记录（K 线买卖标记数据源），按日期升序。
     */
    public List<InvestmentTrade> trades(Long id) {
        Long userId = UserContext.getUserId();
        if (investmentMapper.findById(id, userId) == null) {
            throw BizException.badRequest("标的不存在或不属于当前用户");
        }
        return tradeMapper.listByInvestment(id, userId);
    }

    /**
     * 买入后的历史盈亏（实时计算，不持久化）：逐日 市值 − 台账累计净投入（摊薄口径，与同花顺一致）。
     * 有交易台账的标的（股票/基金买卖记录）按台账逐日演进：净投入 = Σ买 − Σ卖，
     * 市值 = 当日快照价 × 当日持有份额 × 汇率，与走势图同一口径——
     * 清仓后市值归零、净投入为负，盈亏冻结在清仓时刻而不是掉到 0；
     * 无台账的老数据退回按快照的累计投入（invest_amount）计算。
     */
    public List<Map<String, Object>> profitHistory(Long id, String start, String end) {
        Long userId = UserContext.getUserId();
        Investment inv = investmentMapper.findById(id, userId);
        if (inv == null) {
            throw BizException.badRequest("标的不存在或不属于当前用户");
        }
        List<InvestmentTrade> trades = tradeMapper.listByInvestment(id, userId);
        LocalDate firstTrade = trades.isEmpty() ? null : trades.get(0).getTradeDate();
        LocalDate buyDate = inv.getBuyDate();
        // 默认从首笔交易日起展示全部历史（买入以来）；无台账退回买入日/近 30 天
        LocalDate defaultStart = firstTrade != null ? firstTrade
                : (buyDate != null ? buyDate : LocalDate.now().minusDays(29));
        LocalDate startDate = StringUtils.hasText(start) ? IncomeService.parseDate(start, "start")
                : defaultStart;
        LocalDate endDate = StringUtils.hasText(end) ? IncomeService.parseDate(end, "end") : LocalDate.now();
        if (startDate.isAfter(endDate)) {
            throw BizException.badRequest("start 不能晚于 end");
        }
        List<Map<String, Object>> data = new ArrayList<>();
        if (!trades.isEmpty()) {
            // 与走势图/台账重算同一口径：逐日取「不晚于当日」的历史汇率，而不是全程用当前汇率，
            // 否则相邻两天的盈亏差只剩股价变动、丢了汇率微动，与每日走势的「当日盈亏」对不上
            List<com.project.person.admin.model.ExchangeRate> fxHistory =
                    marketService.fxHistoryForCodeUpTo(inv.getCode(), LocalDate.now());
            BigDecimal fallbackFx = StringUtils.hasText(inv.getCode())
                    ? marketService.fxRateForCode(inv.getCode()) : BigDecimal.ONE;
            int ti = 0;
            int shares = 0;
            // 累计净投入（摊薄）：BUY +amount、SELL −amount，按交易日演进
            BigDecimal cumNet = BigDecimal.ZERO;
            for (DailySnapshot s : snapshotMapper.listByRange(userId, DailySnapshot.KIND_INVEST, startDate, endDate)) {
                if (!s.getRefId().equals(id)) {
                    continue;
                }
                // 显式传了早于首笔交易的 start 时，买入前的日期无持仓，跳过避免盈亏虚高
                if (firstTrade != null && s.getSnapshotDate().isBefore(firstTrade)) {
                    continue;
                }
                while (ti < trades.size() && !trades.get(ti).getTradeDate().isAfter(s.getSnapshotDate())) {
                    InvestmentTrade t = trades.get(ti++);
                    if (InvestmentTrade.TYPE_BUY.equals(t.getTradeType())) {
                        shares += t.getShares() == null ? 0 : t.getShares();
                        cumNet = cumNet.add(t.getAmount() == null ? BigDecimal.ZERO : t.getAmount());
                    } else {
                        shares -= Math.min(t.getShares() == null ? 0 : t.getShares(), shares);
                        cumNet = cumNet.subtract(t.getAmount() == null ? BigDecimal.ZERO : t.getAmount());
                    }
                }
                BigDecimal fxDay = fxForDay(fxHistory, s.getSnapshotDate(), fallbackFx);
                BigDecimal value;
                if (s.getPrice() != null && s.getPrice().compareTo(BigDecimal.ZERO) > 0 && fxDay != null) {
                    value = s.getPrice().multiply(BigDecimal.valueOf(shares)).multiply(fxDay)
                            .setScale(2, RoundingMode.HALF_UP);
                } else {
                    value = s.getTotalValue() == null ? BigDecimal.ZERO : s.getTotalValue();
                }
                Map<String, Object> point = new HashMap<>();
                point.put("date", s.getSnapshotDate());
                point.put("profit", value.subtract(cumNet).setScale(2, RoundingMode.HALF_UP));
                data.add(point);
            }
            return data;
        }
        // 无台账老数据：按快照累计投入兜底
        BigDecimal fallbackAmount = inv.getAmount() == null ? BigDecimal.ZERO : inv.getAmount();
        for (DailySnapshot s : snapshotMapper.listByRange(userId, DailySnapshot.KIND_INVEST, startDate, endDate)) {
            if (!s.getRefId().equals(id) || buyDate != null && s.getSnapshotDate().isBefore(buyDate)) {
                continue;
            }
            BigDecimal value = s.getTotalValue() == null ? BigDecimal.ZERO : s.getTotalValue();
            BigDecimal cost = s.getInvestAmount() != null ? s.getInvestAmount() : fallbackAmount;
            Map<String, Object> point = new HashMap<>();
            point.put("date", s.getSnapshotDate());
            point.put("profit", value.subtract(cost).setScale(2, RoundingMode.HALF_UP));
            data.add(point);
        }
        return data;
    }

    /** 写入某日市值快照：同时更新标的当前市值并记录当日快照 */
    @Transactional
    public void addValue(Long id, InvestmentValueHistory history) {
        Long userId = UserContext.getUserId();
        if (investmentMapper.findById(id, userId) == null) {
            throw BizException.badRequest("标的不存在或不属于当前用户");
        }
        if (history == null || history.getValueDate() == null || history.getMarketValue() == null) {
            throw BizException.badRequest("valueDate 和 value 不能为空");
        }
        history.setUserId(userId);
        history.setInvestmentId(id);
        valueHistoryMapper.insert(history);
        investmentMapper.updateCurrentValue(id, userId, history.getMarketValue());
        snapshotService.recordToday(userId);
    }

    /**
     * 校验行情代码（需求：新增/修改时若填写了代码，必须真实存在并加入缓存）。
     * 用户填裸代码（A股6位/港股5位/基金6位），先按类型补全市场前缀再调三方接口校验；
     * 校验通过会顺带把代码加入行情缓存（缓存里没有的话），下次定时任务即可拉取。
     */
    private void checkCode(Investment investment) {
        if (!StringUtils.hasText(investment.getCode())) {
            investment.setCode(null);
            return;
        }
        // 按类型补全前缀（sh/sz/bj/hk），裸代码即可保存
        String resolved = marketService.resolveCode(investment.getType(), investment.getCode());
        // 调三方接口确认代码存在；不存在直接抛 BizException 阻断保存
        MarketQuote quote = marketService.validateAndCache(resolved);
        investment.setCode(quote.getCode());
        // 名称为空时用行情名称兜底（腾讯/天天基金返回的中文名）
        if (!StringUtils.hasText(investment.getName()) && StringUtils.hasText(quote.getName())) {
            investment.setName(quote.getName());
        }
    }

    /**
     * 港股等外币计价标的的汇率折算：有行情代码且属港股的取 HKD→CNY 汇率，其余为 1（人民币计价）。
     * 汇率不可用时抛业务异常，避免按错误币种金额落库。
     */
    private BigDecimal requireFx(Investment inv) {
        if (inv == null || !StringUtils.hasText(inv.getCode())) {
            return BigDecimal.ONE;
        }
        BigDecimal fx = marketService.fxRateForCode(inv.getCode());
        if (fx == null) {
            throw BizException.badRequest("港币汇率暂不可用，请稍后重试");
        }
        return fx;
    }

    /**
     * 指定交易日期的汇率折算：港股取该日 HKD→CNY 历史汇率（库中不晚于该日的最近一条，
     * 该日前无记录退回最新一条），其余人民币计价返回 1。历史买卖按当日汇率折算金额。
     */
    private BigDecimal requireFxAt(Investment inv, LocalDate tradeDate) {
        if (inv == null || !StringUtils.hasText(inv.getCode())) {
            return BigDecimal.ONE;
        }
        BigDecimal fx = marketService.fxRateForCodeAt(inv.getCode(), tradeDate);
        if (fx == null) {
            throw BizException.badRequest("港币汇率暂不可用，请稍后重试");
        }
        return fx;
    }

    /** 渠道同步来的投资由渠道侧维护数据，不支持编辑/卖出/删除 */
    private void checkNotChannelSynced(Investment inv) {
        if (StringUtils.hasText(inv.getSourceChannel())) {
            throw BizException.badRequest("该投资来自外部渠道（" + inv.getSourceChannel()
                    + "）同步，由渠道侧维护，不支持此操作");
        }
    }

    private void checkBasic(Investment investment) {
        if (investment == null || !StringUtils.hasText(investment.getName())) {
            throw BizException.badRequest("标的名称不能为空");
        }
        if (!StringUtils.hasText(investment.getType())) {
            throw BizException.badRequest("标的类型不能为空");
        }
        if (investment.getBuyDate() == null) {
            throw BizException.badRequest("买入日期不能为空");
        }
        if (investment.getBuyDate().isAfter(LocalDate.now())) {
            throw BizException.badRequest("买入日期不能晚于今天");
        }
        if (investment.getFeeRate() != null
                && (investment.getFeeRate().compareTo(BigDecimal.ZERO) < 0
                || investment.getFeeRate().compareTo(new BigDecimal("100")) >= 0)) {
            throw BizException.badRequest("交易费率需在 0 ~ 100 之间（如 0.03 表示 0.03%）");
        }
        if (investment.getFeeFixed() != null && investment.getFeeFixed().compareTo(BigDecimal.ZERO) < 0) {
            throw BizException.badRequest("每笔手续费不能为负");
        }
    }

    /** 交易费率（小数形式，如 0.0003 = 0.03%）：feeRate 字段存百分数（0.03），null 按 0 */
    private BigDecimal feeRateDecimal(Investment inv) {
        if (inv == null || inv.getFeeRate() == null) {
            return BigDecimal.ZERO;
        }
        return inv.getFeeRate().divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
    }

    /** 本笔固定手续费（元）：单笔入参传了用入参，否则退回投资上的默认值，均无则 0 */
    private BigDecimal feeFixedFor(Investment inv, BigDecimal tradeFee) {
        if (tradeFee != null) {
            return tradeFee.max(BigDecimal.ZERO);
        }
        return inv != null && inv.getFeeFixed() != null ? inv.getFeeFixed() : BigDecimal.ZERO;
    }
}
