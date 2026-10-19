package com.project.person.admin.service;

import com.project.person.admin.common.BizException;
import com.project.person.admin.common.UserContext;
import com.project.person.admin.mapper.AssetMapper;
import com.project.person.admin.mapper.ExternalChannelMapper;
import com.project.person.admin.mapper.InvestmentMapper;
import com.project.person.admin.model.ExternalChannel;
import com.project.person.admin.model.ExternalSyncRequest;
import com.project.person.admin.model.Investment;
import com.project.person.admin.model.MarketQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 外部渠道同步服务。
 * 用户需先在投资页手动添加渠道（填写渠道账户名，同用户同渠道同账户名唯一），
 * 同步数据由本机辅助程序（如 THSAutoTrader 的推送脚本）上报；
 * 落库前校验上报的账户名与登记的一致，否则整条拒绝。
 * 对账规则：
 *  1. 持仓有、系统无 → 自动新增投资（不扣资产，存续口径，备注标记来源）
 *  2. 双方都有 → 股数不同则按系统现价折算差额联动出资资产后更新股数
 *  3. 系统有、持仓无 → 不自动删除，仅在同步结果中提示数量
 */
@Service
public class ExternalChannelService {

    private static final Logger log = LoggerFactory.getLogger(ExternalChannelService.class);

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ERROR = "SYNC_ERROR";
    private static final String REMARK_TAG = "同花顺同步";

    @Autowired
    private ExternalChannelMapper channelMapper;

    @Autowired
    private InvestmentMapper investmentMapper;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private SnapshotService snapshotService;

    @Autowired
    private MarketService marketService;

    public List<ExternalChannel> list() {
        return channelMapper.listByUser(UserContext.getUserId());
    }

    /** 手动添加渠道：仅支持已登记的渠道类型；账户名格式校验 + 唯一性校验 */
    @Transactional
    public ExternalChannel add(ExternalChannel channel) {
        Long userId = UserContext.getUserId();
        if (channel == null || !ExternalChannel.CHANNEL_THS.equals(channel.getChannel())) {
            throw BizException.badRequest("暂不支持该渠道，目前仅支持同花顺（THS）");
        }
        String account = channel.getAccountName() == null ? "" : channel.getAccountName().trim();
        if (!StringUtils.hasText(account) || account.length() > 64) {
            throw BizException.badRequest("请填写正确的渠道账户名（同花顺登录用户名）");
        }
        if (channelMapper.findByAccount(userId, ExternalChannel.CHANNEL_THS, account) != null) {
            throw BizException.badRequest("该同花顺账户已添加，请勿重复添加");
        }
        channel.setId(null);
        channel.setUserId(userId);
        channel.setAccountName(account);
        channel.setStatus(STATUS_ACTIVE);
        // 生成推送凭证：部署到公网后，同步接口凭此 key 认证（不再依赖登录态）
        channel.setApiKey(java.util.UUID.randomUUID().toString().replace("-", ""));
        channel.setRemark(channel.getRemark() == null ? "" : channel.getRemark());
        channelMapper.insert(channel);
        return channel;
    }

    @Transactional
    public void delete(Long id) {
        Long userId = UserContext.getUserId();
        if (channelMapper.delete(id, userId) == 0) {
            throw BizException.badRequest("渠道不存在或不属于当前用户");
        }
    }

    /**
     * 接收渠道持仓推送并自动对账。整个对账在一个事务里，失败全量回滚并记渠道状态。
     * 认证：必须携带 X-Api-Key（添加渠道时生成），按 key 定位渠道并确定归属用户，
     * 请求体里的渠道/账户名还必须与登记的一致；另做每 key 每分钟 12 次的简单限流。
     */
    @Transactional
    public String sync(String apiKey, ExternalSyncRequest req) {
        if (!StringUtils.hasText(apiKey)) {
            throw new BizException(401, "缺少 X-Api-Key 推送凭证");
        }
        checkRateLimit(apiKey.trim());
        ExternalChannel channel = channelMapper.findByApiKey(apiKey.trim());
        // 关键校验一：key 必须对应一个已登记且可用的渠道，否则一律拒绝
        if (channel == null) {
            throw new BizException(401, "推送凭证无效");
        }
        if (!STATUS_ACTIVE.equals(channel.getStatus())) {
            throw new BizException(403, "该渠道已停用（" + channel.getStatus() + "），无法同步");
        }
        if (req == null || !StringUtils.hasText(req.getChannel()) || !StringUtils.hasText(req.getAccount())) {
            throw BizException.badRequest("同步请求缺少渠道或账户名");
        }
        String channelType = req.getChannel().trim().toUpperCase(Locale.ROOT);
        // 关键校验二：请求体的渠道 + 账户名必须与 key 对应的登记记录一致，
        // 防止持有一把 key 的用户伪造他人账户名推送
        if (!channelType.equals(channel.getChannel())
                || !req.getAccount().trim().equals(channel.getAccountName())) {
            throw BizException.badRequest("渠道或账户名与登记信息不一致，已拒绝");
        }
        Long userId = channel.getUserId();
        List<ExternalSyncRequest.Position> positions = req.getPositions() == null
                ? java.util.Collections.emptyList() : req.getPositions();
        return reconcile(userId, channel, positions);
    }

    /** 对账主逻辑：仅推送模式使用。规则见类注释。 */
    private String reconcile(Long userId, ExternalChannel channel, List<ExternalSyncRequest.Position> positions) {
        String channelType = channel.getChannel();

        int created = 0, updated = 0, skipped = 0;
        Set<String> syncedCodes = new HashSet<>();
        for (ExternalSyncRequest.Position p : positions) {
            String code = normalizeCode(p == null ? null : p.getCode());
            if (code == null || p.getShares() == null || p.getShares() <= 0) {
                skipped++;
                continue;
            }
            syncedCodes.add(code);
            try {
                Investment existing = investmentMapper.findByCode(userId, code);
                if (existing == null) {
                    createFromPosition(userId, p, code, channelType);
                    created++;
                } else if (syncShares(userId, existing, p.getShares())) {
                    updated++;
                }
            } catch (Exception e) {
                // 单条失败不阻断整批同步，记入跳过数
                skipped++;
                log.warn("渠道持仓 [{}] 同步失败，跳过: {}", code, e.getMessage(), e);
            }
        }
        // 系统里有、本次持仓没有的不自动删除，仅提示
        int missing = 0;
        for (Investment inv : investmentMapper.list(userId)) {
            if (StringUtils.hasText(inv.getCode()) && inv.getSourceChannel() != null
                    && !syncedCodes.contains(inv.getCode().toLowerCase(Locale.ROOT))) {
                missing++;
            }
        }
        String msg = String.format("同步成功：新增 %d，更新 %d，跳过 %d；本地 %d 只同步来源持仓未出现在渠道持仓中（未自动删除）",
                created, updated, skipped, missing);
        channelMapper.touchSync(channel.getId(), STATUS_ACTIVE, LocalDateTime.now(), msg);
        log.info("外部渠道同步 [{}:{}] {}", channelType, channel.getAccountName(), msg);
        return msg;
    }

    /** 简单限流：每个 api key 一分钟内最多 12 次同步推送（内存窗口计数，够用即可） */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> RATE_WINDOWS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private void checkRateLimit(String apiKey) {
        long now = System.currentTimeMillis();
        long[] window = RATE_WINDOWS.compute(apiKey, (k, old) ->
                (old == null || now - old[0] > 60_000) ? new long[]{now, 0} : old);
        synchronized (window) {
            if (++window[1] > 12) {
                throw new BizException(429, "同步过于频繁，请一分钟后再试");
            }
        }
    }

    /** 归一化行情代码：小写、去空白；非 sh/sz/bj/hk 格式返回 null（交由调用方跳过） */
    private String normalizeCode(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        String c = code.trim().toLowerCase(Locale.ROOT);
        return marketService.isStockCode(c) ? c : null;
    }

    /**
     * 渠道持仓里有、系统没有：新增投资（存续口径，不扣资产），成本 = 股数 × 成本价 × 汇率。
     * 当前市值按实时行情计算（股数 × 最新价 × 汇率），拉不到行情时退回按成本计值；
     * 创建成功后异步回填该股票有史以来的历史日 K（走势图即刻展示完整历史）。
     */
    private void createFromPosition(Long userId, ExternalSyncRequest.Position p, String code, String channelType) {
        BigDecimal fx = marketService.fxRateForCode(code);
        if (fx == null) {
            throw BizException.badRequest("港币汇率暂不可用，无法同步港股持仓 " + code);
        }
        BigDecimal costPrice = p.getCostPrice() == null || p.getCostPrice().compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO : p.getCostPrice();
        BigDecimal total = costPrice.multiply(BigDecimal.valueOf(p.getShares()))
                .multiply(fx).setScale(2, RoundingMode.HALF_UP);
        // 当前市值：优先用实时价（股数 × 最新价 × 汇率），与列表展示口径一致
        BigDecimal currentValue = total;
        MarketQuote quote = marketService.ensureQuoteQuietly(code);
        if (quote != null && quote.getPrice() != null && quote.getPrice().compareTo(BigDecimal.ZERO) > 0) {
            currentValue = quote.getPrice().multiply(BigDecimal.valueOf(p.getShares()))
                    .multiply(fx).setScale(2, RoundingMode.HALF_UP);
        }
        Investment inv = new Investment();
        inv.setUserId(userId);
        inv.setName(StringUtils.hasText(p.getName()) ? p.getName().trim() : code);
        inv.setType(code != null && code.toLowerCase().startsWith("hk") ? "港股" : "A股");
        inv.setCode(code);
        inv.setShares(p.getShares());
        inv.setAmount(total);
        inv.setCurrentValue(currentValue);
        inv.setBuyDate(LocalDate.now());
        inv.setSourceAssetId(null);
        inv.setSourceChannel(channelType);
        inv.setRemark(REMARK_TAG);
        investmentMapper.insert(inv);
        snapshotService.recordToday(userId);
        // 历史日 K 与手动新增投资同一套机制：走势图没有历史则拉全量回填
        marketService.backfillHistory(userId, inv);
    }

    /**
     * 股数变化：按系统现价（当前市值/原股数）折算新市值，差额联动出资资产（未挂接则不动），
     * 并同步更新累计投入。返回是否有实际变更。
     */
    private boolean syncShares(Long userId, Investment old, int newShares) {
        int oldShares = old.getShares() == null ? 0 : old.getShares();
        if (newShares == oldShares) {
            return false;
        }
        BigDecimal oldValue = old.getCurrentValue() == null ? BigDecimal.ZERO : old.getCurrentValue();
        BigDecimal oldAmount = old.getAmount() == null ? BigDecimal.ZERO : old.getAmount();
        BigDecimal perShare = oldShares > 0
                ? oldValue.divide(BigDecimal.valueOf(oldShares), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal newValue = perShare.multiply(BigDecimal.valueOf(newShares))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal delta = newValue.subtract(oldValue);
        if (old.getSourceAssetId() != null) {
            assetMapper.adjustValue(old.getSourceAssetId(), userId, delta.negate());
        }
        Investment upd = new Investment();
        upd.setId(old.getId());
        upd.setUserId(userId);
        upd.setName(old.getName());
        upd.setType(old.getType());
        upd.setCode(old.getCode());
        upd.setShares(newShares);
        upd.setCurrentValue(newValue);
        upd.setAmount(oldAmount.add(delta));
        upd.setSourceAssetId(old.getSourceAssetId());
        upd.setSourceChannel(old.getSourceChannel());
        upd.setFeeRate(old.getFeeRate());
        upd.setFeeFixed(old.getFeeFixed());
        upd.setBuyDate(old.getBuyDate());
        upd.setRemark(old.getRemark());
        investmentMapper.update(upd);
        snapshotService.recordToday(userId);
        return true;
    }
}
