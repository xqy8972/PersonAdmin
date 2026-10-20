package com.project.person.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.person.admin.cache.CacheFacade;
import com.project.person.admin.common.BizException;
import com.project.person.admin.mapper.DailySnapshotMapper;
import com.project.person.admin.mapper.InvestmentMapper;
import com.project.person.admin.mapper.MarketDailyQuoteMapper;
import com.project.person.admin.model.DailySnapshot;
import com.project.person.admin.model.Investment;
import com.project.person.admin.model.MarketDailyQuote;
import com.project.person.admin.model.MarketQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 股票/基金行情服务（业务逻辑都集中在这里，缓存统一走 CacheFacade 门面）：
 * <ul>
 *   <li>启动时遍历投资表中已填 code 的标的，拉取一次填充缓存；</li>
 *   <li>{@link #refresh()} 由 MarketValuePullTask 每 20s 调用（仅交易时段），批量拉取最新价存缓存；</li>
 *   <li>{@link #applyRealtime(List)} 投资列表展示时按缓存实时计算市值（股票=股数×最新价，基金=份额×净值（无份额老数据按比例波动））；</li>
 *   <li>{@link #validateAndCache(String)} 新增投资时校验代码合法性（调三方接口），合法则加入缓存；</li>
 *   <li>{@link #recordDailyClose()} 交易日收盘后把收盘价落库 market_quote。</li>
 * </ul>
 * 数据源（均免费、无需注册，非官方接口无 SLA，解析失败静默跳过）：
 * 股票=腾讯 https://qt.gtimg.cn/q=sh600519（GBK，~分隔）；
 * 基金=天天基金 fundgz 盘中估值 https://fundgz.1234567.com.cn/js/{code}.js（JSONP），
 * 休市/夜间 fundgz 无数据时兜底 pingzhongdata https://fund.eastmoney.com/pingzhongdata/{code}.js
 * （含最新真实净值与成立以来全部日净值，基金历史回填也用它）。
 */
@Service
public class MarketService {

    private static final Logger log = LoggerFactory.getLogger(MarketService.class);

    private static final String CACHE_PREFIX = "market:quote:";
    private static final String TENCENT_URL = "https://qt.gtimg.cn/q=";
    private static final String TENCENT_KLINE_URL = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get";
    private static final String FUND_URL = "https://fundgz.1234567.com.cn/js/{code}.js?rt={ts}";
    private static final String FUND_SEARCH_URL = "https://fundsuggest.eastmoney.com/FundSearch/api/FundSearchAPI.ashx?m=1&key={code}";
    /** 天天基金 pingzhongdata：含基金全历史净值（Data_netWorthTrend），休市时 fundgz 无数据用它兜底 */
    private static final String FUND_PINGZHONG_URL = "https://fund.eastmoney.com/pingzhongdata/{code}.js?v={ts}";
    /** 历史日 K 每页条数（腾讯 fqkline 单次上限约 800）与翻页上限（30 页 ≈ 60+ 年，防止异常死循环） */
    private static final int KLINE_PAGE_SIZE = 800;
    private static final int KLINE_MAX_PAGES = 30;
    private static final Pattern TENCENT_PATTERN = Pattern.compile("v_(\\w+)=\"([^\"]*)\"");
    /** pingzhongdata 中的基金名称与全历史净值数组 */
    private static final Pattern PZ_NAME_PATTERN = Pattern.compile("fS_name = \"([^\"]*)\"");
    private static final Pattern PZ_NAV_PATTERN = Pattern.compile("Data_netWorthTrend = (\\[.*?\\]);", Pattern.DOTALL);
    /** A 股代码：sh/sz/bj + 6 位数字 */
    private static final Pattern CN_STOCK_CODE_PATTERN = Pattern.compile("^(sh|sz|bj)\\d{6}$");
    /** 港股（港股通）代码：hk + 5 位数字，如 hk01810 小米 */
    private static final Pattern HK_STOCK_CODE_PATTERN = Pattern.compile("^hk\\d{5}$");
    /** 纯 6 位数字视为场外基金代码 */
    private static final Pattern FUND_CODE_PATTERN = Pattern.compile("^\\d{6}$");

    public static final String MARKET_CN = "CN";
    public static final String MARKET_HK = "HK";
    public static final String MARKET_FUND = "FUND";

    @Autowired
    private CacheFacade cacheFacade;

    @Autowired
    @Qualifier("marketRestTemplate")
    private RestTemplate restTemplate;

    @Autowired
    private InvestmentMapper investmentMapper;

    @Autowired
    private MarketDailyQuoteMapper dailyQuoteMapper;

    @Autowired
    private SnapshotService snapshotService;

    @Autowired
    private DailySnapshotMapper snapshotMapper;

    @Autowired
    private FxService fxService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 服务启动：遍历所有已填行情代码的投资标的，拉取一次填充缓存（失败不阻塞启动） */
    @PostConstruct
    public void initOnStartup() {
        try {
            refresh();
            log.info("行情缓存初始化完成，共 {} 个标的", cacheQuoteCount());
        } catch (Exception e) {
            log.warn("行情缓存初始化失败（可能是无网络），将由定时任务继续重试: {}", e.getMessage());
        }
        // 为历史遗留（回填功能上线前创建）的带码投资补回填，后台线程执行不阻塞启动
        // （不能走 @Async 自调用——同类内部调用不经过代理不会异步）
        Thread backfillThread = new Thread(this::backfillMissingHistory);
        backfillThread.setDaemon(true);
        backfillThread.setName("market-backfill-on-boot");
        backfillThread.start();
    }

    /**
     * 启动时补齐历史：遍历所有带行情代码的投资，还没有历史行情的（如回填功能上线前创建的）
     * 触发一次回填，无需用户删除重建。后台线程中执行。
     */
    private void backfillMissingHistory() {
        List<Investment> all;
        try {
            all = investmentMapper.listAllWithCode();
        } catch (Exception e) {
            log.warn("启动回填扫描失败: {}", e.getMessage());
            return;
        }
        for (Investment inv : all) {
            try {
                doBackfillHistory(inv.getUserId(), inv);
            } catch (Exception e) {
                log.warn("投资 [{}] 启动回填失败: {}", inv.getCode(), e.getMessage());
            }
        }
    }

    /** 拉取所有在库 code 的最新行情并存入缓存（启动 / 手动刷新 / 收盘落库前刷新共用，无条件拉全部） */
    public void refresh() {
        List<String> codes = investmentMapper.listDistinctCodes();
        if (codes == null || codes.isEmpty()) {
            return;
        }
        List<String> stockCodes = new ArrayList<>();
        List<String> fundCodes = new ArrayList<>();
        for (String code : codes) {
            if (isStockCode(code)) {
                stockCodes.add(code.toLowerCase());
            } else if (isFundCode(code)) {
                fundCodes.add(code);
            }
        }
        if (!stockCodes.isEmpty()) {
            pullStocks(stockCodes);
        }
        for (String fundCode : fundCodes) {
            pullFund(fundCode);
        }
    }

    /**
     * 定时任务调用：按市场开收盘时段选择性拉取——
     * A 股/基金在 A 股交易时段拉取，港股在港股交易时段拉取（两者开收盘时间与假期不同）。
     */
    public void refreshIfTrading() {
        List<String> codes = investmentMapper.listDistinctCodes();
        if (codes == null || codes.isEmpty()) {
            return;
        }
        boolean cnOpen = isTradingTime();
        boolean hkOpen = isHkTradingTime();
        List<String> stockCodes = new ArrayList<>();
        List<String> fundCodes = new ArrayList<>();
        for (String code : codes) {
            String market = marketOf(code);
            if (MARKET_CN.equals(market) && cnOpen) {
                stockCodes.add(code.toLowerCase());
            } else if (MARKET_HK.equals(market) && hkOpen) {
                stockCodes.add(code.toLowerCase());
            } else if (MARKET_FUND.equals(market)) {
                // 场外基金：实时估值仅盘中存在，但 pingzhongdata 兜底能拿到最新真实净值，任何时段都拉
                fundCodes.add(code);
            }
        }
        if (!stockCodes.isEmpty()) {
            pullStocks(stockCodes);
        }
        for (String fundCode : fundCodes) {
            pullFund(fundCode);
        }
    }

    /** 判断代码所属市场：CN=A股 / HK=港股通 / FUND=场外基金 / null=未知 */
    public String marketOf(String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim().toLowerCase();
        if (CN_STOCK_CODE_PATTERN.matcher(c).matches()) {
            return MARKET_CN;
        }
        if (HK_STOCK_CODE_PATTERN.matcher(c).matches()) {
            return MARKET_HK;
        }
        if (FUND_CODE_PATTERN.matcher(c).matches()) {
            return MARKET_FUND;
        }
        return null;
    }

    /**
     * 代码对应的汇率（外币→人民币）。港股返回 HKD 兑 CNY 汇率（取不到返回 null），
     * A 股/基金本身就是人民币计价返回 1。
     */
    public BigDecimal fxRateForCode(String code) {
        if (MARKET_HK.equals(marketOf(code))) {
            return fxService.getRate("HKD");
        }
        return BigDecimal.ONE;
    }

    /**
     * 指定日期的汇率（外币→人民币）：港股取该日 HKD→CNY 历史汇率（取不到返回 null），
     * A 股/基金人民币计价返回 1。用于补录历史买卖/台账逐日重算按当日汇率折算。
     */
    public BigDecimal fxRateForCodeAt(String code, java.time.LocalDate date) {
        if (MARKET_HK.equals(marketOf(code))) {
            return fxService.getRateAt("HKD", date);
        }
        return BigDecimal.ONE;
    }

    /** 港股标的不晚于指定日期的 HKD 汇率历史（升序）；非港股返回空表（人民币无需折算） */
    public java.util.List<com.project.person.admin.model.ExchangeRate> fxHistoryForCodeUpTo(String code, java.time.LocalDate date) {
        if (MARKET_HK.equals(marketOf(code))) {
            return fxService.historyUpTo("HKD", date);
        }
        return java.util.Collections.emptyList();
    }

    /** 投资列表展示时调用：按缓存中的最新行情实时计算市值（不改数据库） */
    public void applyRealtime(List<Investment> investments) {
        if (investments == null) {
            return;
        }
        for (Investment inv : investments) {
            if (!StringUtils.hasText(inv.getCode())) {
                continue;
            }
            MarketQuote quote = getQuote(inv.getCode());
            if (quote == null || quote.getPrice() == null) {
                continue;
            }
            // 盘中标记：行情更新于今日且当前处于该市场交易时段（A 股/港股/基金时段不同）
            inv.setLive(quote.getUpdateTime() != null
                    && quote.getUpdateTime().toLocalDate().equals(LocalDate.now())
                    && isTradingTimeForMarket(marketOf(inv.getCode())));
            if (MarketQuote.TYPE_STOCK.equals(quote.getType())) {
                // 股票：市值 = 股数 × 最新价（港股再 × 港币汇率折算人民币）；无股数的场内 ETF 按价格比例折算
                BigDecimal fx = fxRateForCode(inv.getCode());
                if (fx == null) {
                    continue;
                }
                if (inv.getShares() != null && inv.getShares() > 0) {
                    BigDecimal value = quote.getPrice()
                            .multiply(BigDecimal.valueOf(inv.getShares()))
                            .multiply(fx)
                            .setScale(2, RoundingMode.HALF_UP);
                    inv.setCurrentValue(value);
                    inv.setPrice(quote.getPrice());
                } else if (quote.getPrevPrice() != null
                        && quote.getPrevPrice().compareTo(BigDecimal.ZERO) > 0
                        && inv.getCurrentValue() != null) {
                    BigDecimal value = inv.getCurrentValue()
                            .multiply(quote.getPrice())
                            .divide(quote.getPrevPrice(), 2, RoundingMode.HALF_UP);
                    inv.setCurrentValue(value);
                    inv.setPrice(quote.getPrice());
                }
            } else {
                // 基金（人民币计价，无汇率）：有份额时市值 = 份额 × 最新净值/估算净值（精确）
                if (inv.getShares() != null && inv.getShares() > 0) {
                    BigDecimal value = quote.getPrice()
                            .multiply(BigDecimal.valueOf(inv.getShares()))
                            .setScale(2, RoundingMode.HALF_UP);
                    inv.setCurrentValue(value);
                    inv.setPrice(quote.getPrice());
                } else if (quote.getPrevPrice() != null
                        && quote.getPrevPrice().compareTo(BigDecimal.ZERO) > 0
                        && inv.getCurrentValue() != null) {
                    // 无份额的历史数据：持有市值随估算净值同比例波动（估值基于上季持仓，非真实净值）
                    BigDecimal value = inv.getCurrentValue()
                            .multiply(quote.getPrice())
                            .divide(quote.getPrevPrice(), 2, RoundingMode.HALF_UP);
                    inv.setCurrentValue(value);
                    inv.setPrice(quote.getPrice());
                }
            }
        }
    }

    /**
     * 按投资类型把用户输入的裸代码补全为内部带市场前缀的代码（新增/编辑投资、行情校验共用）：
     * A 股 6 位数字按首位自动识别市场（6/9→沪 sh，4/8→北交所 bj，其余→深 sz），也兼容已带前缀的输入；
     * 港股 1~5 位数字补零成 hk+5 位（如 1810/01810 → hk01810），也兼容已带 hk 前缀；
     * 基金 6 位数字原样；其他类型原样返回（代码选填）。
     */
    public String resolveCode(String type, String code) {
        String c = code == null ? "" : code.trim().toLowerCase();
        if ("A股".equals(type)) {
            if (c.matches("(sh|sz|bj)\\d{6}")) {
                return c;
            }
            if (c.matches("\\d{6}")) {
                char f = c.charAt(0);
                String m = (f == '6' || f == '9') ? "sh" : (f == '4' || f == '8') ? "bj" : "sz";
                return m + c;
            }
            throw BizException.badRequest("A 股代码为 6 位数字（如 600519 / 000001），系统自动识别沪深北市场");
        }
        if ("港股".equals(type)) {
            if (c.matches("hk\\d{5}")) {
                return c;
            }
            if (c.matches("\\d{1,5}")) {
                return "hk" + String.format("%05d", Integer.parseInt(c));
            }
            throw BizException.badRequest("港股代码为 5 位数字（如 01810），系统自动补全");
        }
        if ("基金".equals(type)) {
            if (c.matches("\\d{6}")) {
                return c;
            }
            throw BizException.badRequest("基金代码为 6 位数字（如 002910）");
        }
        return c;
    }

    /**
     * 新增/修改投资时校验行情代码：调三方接口确认代码存在，合法则加入缓存。
     * 不存在或网络异常均抛出 BizException。
     */
    public MarketQuote validateAndCache(String code) {
        String normalized = normalizeCode(code);
        if (isStockCode(normalized)) {
            Map<String, MarketQuote> result = pullStocks(java.util.Collections.singletonList(normalized));
            MarketQuote quote = result.get(normalized);
            if (quote == null) {
                throw BizException.badRequest("股票代码不存在或暂不支持：" + normalized);
            }
            return quote;
        }
        if (isFundCode(normalized)) {
            MarketQuote quote = pullFund(normalized);
            if (quote == null) {
                // fundgz 休市时可能整段返回空，此时改用天天基金搜索接口确认代码是否存在
                quote = validateFundBySearch(normalized);
            }
            if (quote == null) {
                throw BizException.badRequest("基金代码不存在：" + normalized);
            }
            return quote;
        }
        throw BizException.badRequest("代码格式不正确：A 股为 6 位数字（自动识别沪深北），港股如 01810，基金为 6 位数字");
    }

    /**
     * 天天基金搜索接口兜底校验（fundgz 休市无估值数据时使用）。
     * 存在则返回仅含名称的行情对象（price 为空，等有估值数据时由定时任务补齐）。
     */
    private MarketQuote validateFundBySearch(String fundCode) {
        try {
            String body = restTemplate.getForObject(FUND_SEARCH_URL, String.class, fundCode);
            if (!StringUtils.hasText(body)) {
                return null;
            }
            JsonNode node = objectMapper.readTree(body);
            // 返回结构：{"ErrCode":0,"Datas":[{"CODE":"110022","NAME":"..."}]}
            JsonNode datas = node.isArray() ? node : node.path("Datas");
            if (datas.isArray() && datas.size() > 0
                    && fundCode.equals(datas.get(0).path("CODE").asText())) {
                MarketQuote quote = new MarketQuote();
                quote.setCode(fundCode);
                quote.setType(MarketQuote.TYPE_FUND);
                quote.setName(datas.get(0).path("NAME").asText(null));
                quote.setUpdateTime(LocalDateTime.now());
                cacheFacade.put(CACHE_PREFIX + fundCode, quote);
                return quote;
            }
        } catch (Exception e) {
            log.warn("天天基金搜索校验失败 {}: {}", fundCode, e.getMessage());
        }
        return null;
    }

    /**
     * 确保缓存里有该代码的行情（渠道同步等场景用）：
     * 缓存已有直接返回；没有则调三方接口拉一次（会顺带写入缓存），
     * 拉取失败不抛异常，返回已有的（可能为 null）。
     */
    public MarketQuote ensureQuoteQuietly(String code) {
        MarketQuote quote = getQuote(code);
        if (quote != null && quote.getPrice() != null) {
            return quote;
        }
        try {
            return validateAndCache(code);
        } catch (Exception e) {
            log.warn("补拉行情失败 {}: {}", code, e.getMessage());
            return quote;
        }
    }

    /** 从缓存读取单个标的行情 */
    public MarketQuote getQuote(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        Object v = cacheFacade.get(CACHE_PREFIX + code.trim().toLowerCase());
        return v instanceof MarketQuote ? (MarketQuote) v : null;
    }

    /** 缓存中的全部行情（调试用 / 前端展示） */
    public Map<String, MarketQuote> getAllQuotes() {
        Map<String, MarketQuote> all = new LinkedHashMap<>();
        for (String key : cacheFacade.keys()) {
            if (key.startsWith(CACHE_PREFIX)) {
                Object v = cacheFacade.get(key);
                if (v instanceof MarketQuote) {
                    all.put(key.substring(CACHE_PREFIX.length()), (MarketQuote) v);
                }
            }
        }
        return all;
    }

    /**
     * 收盘落库（手动补录用）：A 股 + 港股两个市场都执行。
     */
    @Transactional
    public void recordDailyClose() {
        recordDailyClose(MARKET_CN);
        recordDailyClose(MARKET_HK);
    }

    /**
     * 指定市场收盘后调用（A 股 15:05 / 港股 16:05，由 DailyMarketValueRecordTask 触发）：
     * 1. 刷新行情，把该市场当日收盘价落库 market_quote；
     * 2. 按收盘价回写涉及这些行情代码的投资 current_value（H2 持久化，港股按当日港币汇率折算人民币），
     *    并给相关用户记录当日快照（daily_snapshot），保证走势图历史连续。
     */
    @Transactional
    public void recordDailyClose(String market) {
        refresh();
        LocalDate today = LocalDate.now();
        int count = 0;
        for (Map.Entry<String, MarketQuote> entry : getAllQuotes().entrySet()) {
            String codeMarket = marketOf(entry.getKey());
            if (!market.equals(codeMarket) && !(MARKET_CN.equals(market) && MARKET_FUND.equals(codeMarket))) {
                continue;
            }
            MarketQuote quote = entry.getValue();
            if (quote.getPrice() == null) {
                continue;
            }
            MarketDailyQuote daily = new MarketDailyQuote();
            daily.setCode(entry.getKey());
            daily.setName(quote.getName());
            daily.setType(quote.getType());
            daily.setTradeDate(today);
            daily.setPrice(quote.getPrice());
            dailyQuoteMapper.upsert(daily);
            count++;
        }

        // 按收盘价回写投资市值（口径与列表实时计算一致；港股乘当日港币汇率）
        Set<Long> affectedUserIds = new HashSet<>();
        int updated = 0;
        for (Investment inv : investmentMapper.listAllWithCode()) {
            if (!market.equals(marketOf(inv.getCode()))) {
                continue;
            }
            MarketQuote quote = getQuote(inv.getCode());
            if (quote == null || quote.getPrice() == null) {
                continue;
            }
            BigDecimal fx = fxRateForCode(inv.getCode());
            if (fx == null) {
                continue;
            }
            BigDecimal newValue = null;
            boolean shareBased = (MarketQuote.TYPE_STOCK.equals(quote.getType())
                    || MarketQuote.TYPE_FUND.equals(quote.getType()))
                    && inv.getShares() != null && inv.getShares() > 0;
            if (shareBased) {
                // 股票/基金：市值 = 数量 × 最新价（股票港股再乘汇率，基金人民币计价 fx=1）
                newValue = quote.getPrice()
                        .multiply(BigDecimal.valueOf(inv.getShares()))
                        .multiply(fx)
                        .setScale(2, RoundingMode.HALF_UP);
            } else if (quote.getPrevPrice() != null
                    && quote.getPrevPrice().compareTo(BigDecimal.ZERO) > 0
                    && inv.getCurrentValue() != null) {
                newValue = inv.getCurrentValue()
                        .multiply(quote.getPrice())
                        .divide(quote.getPrevPrice(), 2, RoundingMode.HALF_UP);
            }
            if (newValue != null) {
                investmentMapper.updateCurrentValue(inv.getId(), inv.getUserId(), newValue);
                affectedUserIds.add(inv.getUserId());
                updated++;
            }
        }

        // 相关用户记录当日快照，资产/投资走势历史保持连续
        for (Long userId : affectedUserIds) {
            snapshotService.recordToday(userId);
        }
        log.info("{} 收盘落库完成：{} 条行情（{}），回写 {} 条投资市值，刷新 {} 个用户快照",
                market, count, today, updated, affectedUserIds.size());
    }

    /**
     * 新增带行情代码的股票/基金投资后调用（异步，不阻塞新增接口）：
     * 若该投资在走势图（daily_snapshot）中还没有任何历史记录，
     * 则从腾讯拉取该股票有史以来的全部日 K（按日期向前分页），
     * 按「收盘价 × 当前股数」逐日写入快照，走势图即刻展示完整历史。
     * 已存在历史则跳过；失败静默（不影响主流程）。
     */
    @Async
    public void backfillHistory(Long userId, Investment inv) {
        try {
            doBackfillHistory(userId, inv);
        } catch (Exception e) {
            log.warn("投资 [{}] 历史日K回填失败: {}", inv == null ? null : inv.getCode(), e.getMessage());
        }
    }

    /** 回填核心逻辑（同步执行，异步由调用方决定）：无历史行情时拉全量历史并按天写快照 */
    private void doBackfillHistory(Long userId, Investment inv) throws java.io.IOException {
            if (inv == null || inv.getId() == null || !StringUtils.hasText(inv.getCode())) {
                return;
            }
            if (!isStockCode(inv.getCode()) && !isFundCode(inv.getCode())) {
                return;
            }
            // 判断是否“已回填过”：以买入日为界。买入日之前的快照只有回填才会产生，
            // 新增流程只会写买入日及之后（和今日）的快照，不能因此跳过回填
            LocalDate cutoff = inv.getBuyDate() != null && inv.getBuyDate().isBefore(LocalDate.now())
                    ? inv.getBuyDate() : LocalDate.now();
            if (snapshotMapper.countHistoryBefore(userId, DailySnapshot.KIND_INVEST,
                    inv.getId(), cutoff) > 0) {
                return;
            }
            int shares = inv.getShares() == null || inv.getShares() <= 0 ? 1 : inv.getShares();
            BarTail tail;
            if (isFundCode(inv.getCode())) {
                tail = backfillFundHistory(userId, inv, shares);
            } else {
                tail = backfillStockHistory(userId, inv, shares);
            }
            fillTail(userId, inv, shares, tail);
    }

    /** 回填数据的最后一个点（日期 + 单价），用于尾部补齐到今日 */
    private static class BarTail {
        private LocalDate date;
        private BigDecimal price;
    }

    /**
     * 尾部补齐：行情数据最晚只到最近一个交易日（周末/节假日无数据），
     * 而新增流程在买入日/今日写的快照是申报成本价，会把曲线末端拉成断崖。
     * 从最后一个数据点逐日补齐到今日（按最近价平推），保证曲线连续。
     */
    private void fillTail(Long userId, Investment inv, int shares, BarTail tail) {
        if (tail == null || tail.date == null || tail.price == null) {
            return;
        }
        BigDecimal fx = fxRateForCode(inv.getCode());
        if (fx == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        BigDecimal unit = tail.price.multiply(fx);
        for (LocalDate d = tail.date.plusDays(1); !d.isAfter(today); d = d.plusDays(1)) {
            DailySnapshot s = new DailySnapshot();
            s.setUserId(userId);
            s.setSnapshotDate(d);
            s.setKind(DailySnapshot.KIND_INVEST);
            s.setRefId(inv.getId());
            s.setName(inv.getName());
            s.setType(inv.getType());
            s.setTotalValue(unit.multiply(BigDecimal.valueOf(shares))
                    .setScale(2, RoundingMode.HALF_UP));
            s.setInvestAmount(inv.getAmount());
            s.setPrice(tail.price);
            snapshotMapper.upsert(s);
        }
    }

    /**
     * 基金历史回填：从 pingzhongdata 的 Data_netWorthTrend（基金成立以来的全部日净值）
     * 按「净值 × 当前份额」逐日写入快照，走势图即刻展示完整历史。人民币计价无汇率。
     */
    private BarTail backfillFundHistory(Long userId, Investment inv, int shares) throws java.io.IOException {
        byte[] bytes = restTemplate.getForObject(FUND_PINGZHONG_URL, byte[].class,
                inv.getCode(), System.currentTimeMillis());
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        String body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        Matcher navMatcher = PZ_NAV_PATTERN.matcher(body);
        if (!navMatcher.find()) {
            return null;
        }
        JsonNode trend = objectMapper.readTree(navMatcher.group(1));
        int total = 0;
        BarTail tail = new BarTail();
        for (JsonNode point : trend) {
            BigDecimal nav = parseDecimal(point.path("y").asText(null));
            long epochMilli = point.path("x").asLong();
            if (nav == null || epochMilli <= 0 || nav.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            LocalDate date = java.time.Instant.ofEpochMilli(epochMilli)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            // 买入日之前未持有：市值记 0（价格保留给走势与台账重算），买入日（含）之后按份额计市值
            boolean held = inv.getBuyDate() == null || !date.isBefore(inv.getBuyDate());
            DailySnapshot s = new DailySnapshot();
            s.setUserId(userId);
            s.setSnapshotDate(date);
            s.setKind(DailySnapshot.KIND_INVEST);
            s.setRefId(inv.getId());
            s.setName(inv.getName());
            s.setType(inv.getType());
            s.setTotalValue(held ? nav.multiply(BigDecimal.valueOf(shares))
                    .setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            // 买入日（含）之后才有持仓成本概念，记投入金额供盈亏曲线计算；之前未持有，盈亏记 0
            s.setInvestAmount(held ? inv.getAmount() : null);
            s.setPrice(nav);
            snapshotMapper.upsert(s);
            tail.date = date;
            tail.price = nav;
            total++;
        }
        log.info("基金 [{}] 历史净值回填完成：{} 条（{}）", inv.getName(), total, inv.getCode());
        return tail;
    }

    /** 股票历史回填：腾讯 fqkline 分页拉取有史以来的不复权日 K，按「收盘价 × 当前股数」逐日写入快照 */
    private BarTail backfillStockHistory(Long userId, Investment inv, int shares) {
        try {
            // 港股历史按当前港币汇率折算人民币（近似，逐日历史汇率暂无免费源）
            BigDecimal fx = fxRateForCode(inv.getCode());
            if (fx == null) {
                log.warn("投资 [{}] 历史回填跳过：港币汇率不可用", inv.getCode());
                return null;
            }
            int total = 0;
            BarTail tail = null;
            String end = "";
            for (int page = 0; page < KLINE_MAX_PAGES; page++) {
                // 不复权日 K：param 末段 fq 留空。前复权会把早期价格复权成负数（深发展 1991 年收盘为负），
                // 不复权取当日真实成交价，市值口径始终为正
                String url = TENCENT_KLINE_URL + "?param=" + inv.getCode()
                        + ",day,," + end + "," + KLINE_PAGE_SIZE + ",";
                String body = restTemplate.getForObject(url, String.class);
                if (!StringUtils.hasText(body)) {
                    break;
                }
                JsonNode data = objectMapper.readTree(body).path("data").path(inv.getCode());
                // 前复权数据在 qfqday，无复权数据在 day
                JsonNode bars = data.has("qfqday") ? data.get("qfqday") : data.get("day");
                if (bars == null || !bars.isArray() || bars.size() == 0) {
                    break;
                }
                LocalDate earliest = null;
                for (JsonNode bar : bars) {
                    // 字段：日期,开,收,高,低,成交量
                    LocalDate date = LocalDate.parse(bar.get(0).asText());
                    BigDecimal close = new BigDecimal(bar.get(2).asText());
                    if (earliest == null || date.isBefore(earliest)) {
                        earliest = date;
                    }
                    if (close.compareTo(BigDecimal.ZERO) <= 0) {
                        continue;
                    }
                    // 买入日之前未持有：市值记 0（价格保留给走势与台账重算），买入日（含）之后按股数计市值
                    boolean held = inv.getBuyDate() == null || !date.isBefore(inv.getBuyDate());
                    DailySnapshot s = new DailySnapshot();
                    s.setUserId(userId);
                    s.setSnapshotDate(date);
                    s.setKind(DailySnapshot.KIND_INVEST);
                    s.setRefId(inv.getId());
                    s.setName(inv.getName());
                    s.setType(inv.getType());
                    s.setTotalValue(held ? close.multiply(BigDecimal.valueOf(shares)).multiply(fx)
                            .setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                    // 买入日（含）之后才有持仓成本概念，记投入金额供盈亏曲线计算；之前未持有，盈亏记 0
                    s.setInvestAmount(held ? inv.getAmount() : null);
                    s.setPrice(close);
                    snapshotMapper.upsert(s);
                    // 第一页是最近的行情，页内按日期升序，最后一个有效 bar 即最新数据点
                    if (page == 0) {
                        if (tail == null) {
                            tail = new BarTail();
                        }
                        tail.date = date;
                        tail.price = close;
                    }
                }
                total += bars.size();
                // 不足一页说明已到上市首日，否则以本页最早日期前一天为终点继续向前翻
                if (bars.size() < KLINE_PAGE_SIZE || earliest == null) {
                    break;
                }
                end = earliest.minusDays(1).toString();
            }
            log.info("投资 [{}] 历史日K回填完成：{} 条（{}）", inv.getName(), total, inv.getCode());
            return tail;
        } catch (Exception e) {
            log.warn("投资 [{}] 历史日K回填失败: {}", inv == null ? null : inv.getCode(), e.getMessage());
            return null;
        }
    }

    /** 是否 A 股交易时段（周一至周五 09:25-11:30 / 13:00-15:05），供定时任务判断 */
    public boolean isTradingTime() {
        return isTradingTimeForMarket(MARKET_CN);
    }

    /** 是否港股（港股通）交易时段（周一至周五 09:30-12:00 / 13:00-16:05） */
    public boolean isHkTradingTime() {
        return isTradingTimeForMarket(MARKET_HK);
    }

    /** 按市场区分交易时段：A 股 09:25-11:30/13:00-15:05；港股 09:30-12:00/13:00-16:05。均为周一至周五（法定节假日未内置） */
    public boolean isTradingTimeForMarket(String market) {
        LocalDateTime now = LocalDateTime.now();
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime t = now.toLocalTime();
        if (MARKET_HK.equals(market)) {
            boolean morning = !t.isBefore(LocalTime.of(9, 30)) && !t.isAfter(LocalTime.of(12, 0));
            boolean afternoon = !t.isBefore(LocalTime.of(13, 0)) && !t.isAfter(LocalTime.of(16, 5));
            return morning || afternoon;
        }
        boolean morning = !t.isBefore(LocalTime.of(9, 25)) && !t.isAfter(LocalTime.of(11, 30));
        boolean afternoon = !t.isBefore(LocalTime.of(13, 0)) && !t.isAfter(LocalTime.of(15, 5));
        return morning || afternoon;
    }

    /** 是否股票代码（A 股 sh/sz/bj 或港股 hk 前缀） */
    public boolean isStockCode(String code) {
        if (code == null) {
            return false;
        }
        String c = code.trim().toLowerCase();
        return CN_STOCK_CODE_PATTERN.matcher(c).matches()
                || HK_STOCK_CODE_PATTERN.matcher(c).matches();
    }

    public boolean isFundCode(String code) {
        return code != null && FUND_CODE_PATTERN.matcher(code).matches();
    }

    /** 统一格式：去空白、转小写 */
    private String normalizeCode(String code) {
        return code == null ? null : code.trim().toLowerCase();
    }

    private int cacheQuoteCount() {
        int count = 0;
        for (String key : cacheFacade.keys()) {
            if (key.startsWith(CACHE_PREFIX)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 腾讯行情批量拉取（单次上限约 200 只）。
     * 返回 code → 行情；单只失败不影响其他标的。
     */
    private Map<String, MarketQuote> pullStocks(List<String> codes) {
        Map<String, MarketQuote> result = new LinkedHashMap<>();
        try {
            String body = restTemplate.getForObject(TENCENT_URL + String.join(",", codes), String.class);
            if (!StringUtils.hasText(body)) {
                return result;
            }
            Matcher matcher = TENCENT_PATTERN.matcher(body);
            while (matcher.find()) {
                String code = matcher.group(1).toLowerCase();
                if ("pv_none_match".equals(code)) {
                    continue;
                }
                String[] parts = matcher.group(2).split("~", -1);
                // 腾讯字段：1=名称 3=最新价 4=昨收 32=涨跌幅
                if (parts.length < 5 || !StringUtils.hasText(parts[1]) || !StringUtils.hasText(parts[3])) {
                    continue;
                }
                try {
                    MarketQuote quote = new MarketQuote();
                    quote.setCode(code);
                    quote.setType(MarketQuote.TYPE_STOCK);
                    quote.setName(parts[1]);
                    quote.setPrice(new BigDecimal(parts[3]));
                    quote.setPrevPrice(StringUtils.hasText(parts[4]) ? new BigDecimal(parts[4]) : null);
                    if (parts.length > 32 && StringUtils.hasText(parts[32])) {
                        quote.setChangePercent(new BigDecimal(parts[32]));
                    }
                    quote.setUpdateTime(LocalDateTime.now());
                    cacheFacade.put(CACHE_PREFIX + code, quote);
                    result.put(code, quote);
                } catch (NumberFormatException e) {
                    log.warn("腾讯行情解析失败 {}: {}", code, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("拉取腾讯行情失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 天天基金估值拉取（JSONP）。优先 fundgz（数据小、含盘中估算净值）；
     * 休市/夜间 fundgz 整段为空（404 页面）时退回 pingzhongdata 取最新真实净值。
     * 返回行情；代码不存在或请求失败返回 null
     */
    private MarketQuote pullFund(String fundCode) {
        MarketQuote quote = pullFundFromFundgz(fundCode);
        if (quote != null && quote.getPrice() != null) {
            return quote;
        }
        MarketQuote fromPz = pullFundFromPingzhong(fundCode, quote == null ? null : quote.getName());
        return fromPz != null ? fromPz : quote;
    }

    /** fundgz 盘中估值（JSONP）。返回行情；无数据或请求失败返回 null */
    private MarketQuote pullFundFromFundgz(String fundCode) {
        try {
            String body = restTemplate.getForObject(FUND_URL, String.class, fundCode, System.currentTimeMillis());
            // 休市/夜间 fundgz 返回 404 HTML 页面而非 JSONP（页面 CSS 里也有花括号，不能只判 "{"），
            // 非 JSONP 直接判定无数据交给 pingzhongdata 兜底
            if (!StringUtils.hasText(body) || body.indexOf('(') < 0 || body.lastIndexOf(')') < 0) {
                return null;
            }
            String json = body.substring(body.indexOf('(') + 1, body.lastIndexOf(')'));
            if (!StringUtils.hasText(json)) {
                return null;
            }
            JsonNode node = objectMapper.readTree(json);
            MarketQuote quote = new MarketQuote();
            quote.setCode(fundCode);
            quote.setType(MarketQuote.TYPE_FUND);
            quote.setName(node.path("name").asText(null));
            BigDecimal dwjz = parseDecimal(node.path("dwjz").asText(null));
            BigDecimal gsz = parseDecimal(node.path("gsz").asText(null));
            // 优先用盘中估算净值，休市时 fundgz 返回的 gsz 可能为空则退回昨净值
            quote.setPrice(gsz != null ? gsz : dwjz);
            quote.setPrevPrice(dwjz);
            quote.setChangePercent(parseDecimal(node.path("gszzl").asText(null)));
            quote.setUpdateTime(LocalDateTime.now());
            cacheFacade.put(CACHE_PREFIX + fundCode, quote);
            return quote;
        } catch (Exception e) {
            log.warn("拉取基金估值失败 {}: {}", fundCode, e.getMessage());
            return null;
        }
    }

    /**
     * pingzhongdata 兜底（含基金全历史净值）。取 Data_netWorthTrend 最后一期作为最新净值、
     * 倒数第二期作为昨净值，equityReturn 作为日涨跌幅。
     * 返回行情；代码不存在或请求失败返回 null
     */
    private MarketQuote pullFundFromPingzhong(String fundCode, String fallbackName) {
        try {
            // 响应为 UTF-8 且体积较大（数百 KB），用 byte[] 接收后自行解码，避免被 GBK 解码器干扰
            byte[] bytes = restTemplate.getForObject(FUND_PINGZHONG_URL, byte[].class, fundCode, System.currentTimeMillis());
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            String body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (!body.contains("Data_netWorthTrend")) {
                return null;
            }
            Matcher navMatcher = PZ_NAV_PATTERN.matcher(body);
            if (!navMatcher.find()) {
                return null;
            }
            JsonNode trend = objectMapper.readTree(navMatcher.group(1));
            if (!trend.isArray() || trend.size() == 0) {
                return null;
            }
            JsonNode last = trend.get(trend.size() - 1);
            BigDecimal latestNav = parseDecimal(last.path("y").asText(null));
            if (latestNav == null) {
                return null;
            }
            MarketQuote quote = new MarketQuote();
            quote.setCode(fundCode);
            quote.setType(MarketQuote.TYPE_FUND);
            Matcher nameMatcher = PZ_NAME_PATTERN.matcher(body);
            quote.setName(nameMatcher.find() ? nameMatcher.group(1) : fallbackName);
            quote.setPrice(latestNav);
            if (trend.size() > 1) {
                quote.setPrevPrice(parseDecimal(trend.get(trend.size() - 2).path("y").asText(null)));
            }
            quote.setChangePercent(parseDecimal(last.path("equityReturn").asText(null)));
            quote.setUpdateTime(LocalDateTime.now());
            cacheFacade.put(CACHE_PREFIX + fundCode, quote);
            return quote;
        } catch (Exception e) {
            log.warn("pingzhongdata 兜底拉取失败 {}: {}", fundCode, e.getMessage());
            return null;
        }
    }

    private BigDecimal parseDecimal(String text) {
        try {
            return StringUtils.hasText(text) ? new BigDecimal(text) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
