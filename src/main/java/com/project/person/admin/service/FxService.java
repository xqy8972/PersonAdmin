package com.project.person.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.person.admin.cache.CacheFacade;
import com.project.person.admin.mapper.ExchangeRateMapper;
import com.project.person.admin.model.ExchangeRate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 汇率服务：人民币兑美元/日元/港元/欧元/英镑。
 * 数据源 open.er-api.com（免费无 key）为主，exchangerate.fun 备选：返回 1 CNY = rates[X] X，
 * 本服务换算为 1 X = (1/rates[X]) CNY。
 * 当日最新值存缓存（market:fx:{currency}），同时按 (currency, 当日) upsert 落库历史；
 * FxPullTask 每 10 分钟拉取一次。
 */
@Service
public class FxService {

    private static final Logger log = LoggerFactory.getLogger(FxService.class);

    public static final String FX_CACHE_PREFIX = "market:fx:";
    public static final String[] CURRENCIES = {"USD", "JPY", "HKD", "EUR", "GBP"};

    /** 历史汇率回填起点：服务启动检查该日起的每日汇率是否存在，缺失则拉取 */
    public static final LocalDate FX_HISTORY_FROM = LocalDate.of(2025, 1, 1);

    /**
     * 历史汇率数据源（免费无 key，欧洲央行参考汇率）：支持日期区间一次拉全，
     * 返回 1 CNY = rates[date][X] X，换算 1 X = (1/rates[date][X]) CNY，与 refresh() 同口径。
     */
    private static final String[] FX_HISTORY_URLS = {
            "https://api.frankfurter.dev/v1/%s..%s?base=CNY&symbols=USD,JPY,HKD,EUR,GBP",
            "https://api.frankfurter.app/%s..%s?base=CNY&symbols=USD,JPY,HKD,EUR,GBP",
    };

    /**
     * 数据源 open.er-api.com（免费无 key，基于欧洲央行/IMF 数据）：
     * 返回 1 CNY = rates[X] X，本服务换算为 1 X = (1/rates[X]) CNY。
     * exchangerate.fun 作为备选。
     */
    private static final String[] FX_URLS = {
            "https://open.er-api.com/v6/latest/CNY",
            "https://api.exchangerate.fun/latest?base=CNY",
    };

    @Autowired
    private CacheFacade cacheFacade;

    /**
     * 汇率接口响应较慢且偶发超时，使用独立的长超时客户端，
     * 避免占用行情用的 marketRestTemplate（5 秒读超时不够）。
     */
    private final RestTemplate fxRestTemplate = buildFxRestTemplate();

    private static RestTemplate buildFxRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8000);
        factory.setReadTimeout(20000);
        return new RestTemplate(factory);
    }

    @Autowired
    private ExchangeRateMapper exchangeRateMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 启动即拉一次，缓存有初值 */
    @PostConstruct
    public void initOnStartup() {
        try {
            refresh();
        } catch (Exception e) {
            log.warn("汇率缓存初始化失败（可能是无网络），将由定时任务继续重试: {}", e.getMessage());
        }
    }

    /** 拉取最新汇率：当日值写缓存 + 按 (currency, 当日) 落库历史。主源失败自动切备选源。 */
    public void refresh() {
        String body = null;
        Exception lastErr = null;
        for (String url : FX_URLS) {
            try {
                body = getWithBrowserUa(url);
                if (StringUtils.hasText(body)) {
                    break;
                }
            } catch (Exception e) {
                lastErr = e;
                log.warn("汇率源 [{}] 拉取失败: {}", url, e.getMessage());
            }
        }
        if (!StringUtils.hasText(body)) {
            if (lastErr != null) {
                throw new IllegalStateException("所有汇率源均不可用: " + lastErr.getMessage(), lastErr);
            }
            return;
        }
        if (!StringUtils.hasText(body)) {
            return;
        }
        JsonNode rates;
        try {
            rates = objectMapper.readTree(body).path("rates");
        } catch (Exception e) {
            throw new IllegalStateException("汇率接口返回解析失败: " + e.getMessage(), e);
        }
        LocalDate today = LocalDate.now();
        for (String currency : CURRENCIES) {
            JsonNode node = rates.path(currency);
            if (!node.isNumber() || node.asDouble() <= 0) {
                continue;
            }
            // 接口返回 1 CNY = node 单位外币，换算为 1 外币 = 多少人民币
            BigDecimal rate = BigDecimal.ONE.divide(
                    BigDecimal.valueOf(node.asDouble()), 6, RoundingMode.HALF_UP);

            ExchangeRate er = new ExchangeRate();
            er.setCurrency(currency);
            er.setRate(rate);
            er.setRateDate(today);
            er.setUpdateTime(LocalDateTime.now());
            exchangeRateMapper.upsert(er);
            cacheFacade.put(FX_CACHE_PREFIX + currency, er);
        }
        log.info("汇率刷新完成：{} 个币种（{}）", CURRENCIES.length, today);
    }

    /**
     * exchangerate.fun 对默认 UA 返回 403（Cloudflare 1010），需带浏览器 UA。
     */
    private String getWithBrowserUa(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        return fxRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<String>(headers), String.class).getBody();
    }

    /**
     * 历史汇率回填：检查 2025-01-01 起的每日汇率是否已在库中，缺失则从 frankfurter
     * 按日期区间一次性拉取（仅工作日有欧洲央行参考价，周末/节假日由「不晚于当日」查询自然兜底），
     * 换算口径与 refresh() 一致（1 外币 = 1/rate CNY）。返回是否执行了回填。
     */
    public boolean backfillHistory() {
        if (exchangeRateMapper.countOnOrBefore(FX_HISTORY_FROM.plusDays(2)) > 0) {
            return false;
        }
        LocalDate today = LocalDate.now();
        JsonNode rates = null;
        Exception lastErr = null;
        for (String fmt : FX_HISTORY_URLS) {
            String url = String.format(fmt, FX_HISTORY_FROM, today);
            try {
                String body = getWithBrowserUa(url);
                if (StringUtils.hasText(body)) {
                    rates = objectMapper.readTree(body).path("rates");
                    if (rates.isObject() && rates.size() > 0) {
                        break;
                    }
                    rates = null;
                }
            } catch (Exception e) {
                lastErr = e;
                log.warn("历史汇率源 [{}] 拉取失败: {}", url, e.getMessage());
            }
        }
        if (rates == null || !rates.isObject()) {
            log.warn("历史汇率回填失败：所有源均不可用{}", lastErr != null ? "（" + lastErr.getMessage() + "）" : "");
            return false;
        }
        int saved = 0;
        java.util.Iterator<String> dates = rates.fieldNames();
        while (dates.hasNext()) {
            String dateStr = dates.next();
            JsonNode dayRates = rates.path(dateStr);
            LocalDate date;
            try {
                date = LocalDate.parse(dateStr);
            } catch (Exception e) {
                continue;
            }
            for (String currency : CURRENCIES) {
                JsonNode node = dayRates.path(currency);
                if (!node.isNumber() || node.asDouble() <= 0) {
                    continue;
                }
                ExchangeRate er = new ExchangeRate();
                er.setCurrency(currency);
                er.setRate(BigDecimal.ONE.divide(
                        BigDecimal.valueOf(node.asDouble()), 6, RoundingMode.HALF_UP));
                er.setRateDate(date);
                exchangeRateMapper.upsert(er);
                saved++;
            }
        }
        log.info("历史汇率回填完成：{} 起共 {} 条（{} 个币种）", FX_HISTORY_FROM, saved, CURRENCIES.length);
        return true;
    }

    /**
     * 读取某币种兑人民币汇率：缓存 → 立即拉取一次 → 库中最近一条，都没有返回 null。
     */
    public BigDecimal getRate(String currency) {
        if (!StringUtils.hasText(currency)) {
            return null;
        }
        String key = FX_CACHE_PREFIX + currency.toUpperCase();
        Object cached = cacheFacade.get(key);
        if (cached instanceof ExchangeRate && ((ExchangeRate) cached).getRate() != null) {
            return ((ExchangeRate) cached).getRate();
        }
        try {
            refresh();
        } catch (Exception e) {
            log.warn("汇率即时拉取失败 {}: {}", currency, e.getMessage());
        }
        cached = cacheFacade.get(key);
        if (cached instanceof ExchangeRate && ((ExchangeRate) cached).getRate() != null) {
            return ((ExchangeRate) cached).getRate();
        }
        ExchangeRate latest = exchangeRateMapper.findLatest(currency.toUpperCase());
        return latest == null ? null : latest.getRate();
    }

    /**
     * 读取指定日期的历史汇率：库中不晚于该日的最近一条；该日之前无记录则退回最新一条；都没有返回 null。
     * 用于补录历史买入/卖出时按当日汇率折算金额。
     */
    public BigDecimal getRateAt(String currency, LocalDate date) {
        if (!StringUtils.hasText(currency) || date == null) {
            return null;
        }
        String c = currency.toUpperCase();
        ExchangeRate er = exchangeRateMapper.findOnOrBefore(c, date);
        if (er == null || er.getRate() == null) {
            er = exchangeRateMapper.findLatest(c);
        }
        return er == null ? null : er.getRate();
    }

    /** 某币种不晚于指定日期的全部历史汇率（升序）；无记录返回空表 */
    public java.util.List<ExchangeRate> historyUpTo(String currency, LocalDate date) {
        if (!StringUtils.hasText(currency) || date == null) {
            return java.util.Collections.emptyList();
        }
        return exchangeRateMapper.listUpTo(currency.toUpperCase(), date);
    }

    /** 缓存中的全部汇率（当日） */
    public Map<String, ExchangeRate> getAll() {
        Map<String, ExchangeRate> all = new LinkedHashMap<>();
        for (String currency : CURRENCIES) {
            Object v = cacheFacade.get(FX_CACHE_PREFIX + currency);
            if (v instanceof ExchangeRate) {
                all.put(currency, (ExchangeRate) v);
            }
        }
        return all;
    }
}
