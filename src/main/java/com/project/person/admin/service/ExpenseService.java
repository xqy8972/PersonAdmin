package com.project.person.admin.service;

import com.project.person.admin.common.BizException;
import com.project.person.admin.common.PageResult;
import com.project.person.admin.common.UserContext;
import com.project.person.admin.mapper.AssetMapper;
import com.project.person.admin.mapper.ExpenseMapper;
import com.project.person.admin.mapper.InvestmentMapper;
import com.project.person.admin.model.Asset;
import com.project.person.admin.model.Expense;
import com.project.person.admin.model.Investment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

@Service
public class ExpenseService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseService.class);

    @Autowired
    private ExpenseMapper expenseMapper;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private AssetSupport assetSupport;

    @Autowired
    private SnapshotService snapshotService;

    @Autowired
    private InvestmentMapper investmentMapper;

    @Autowired
    private InvestmentService investmentService;

    @Autowired
    private AssetFlowService assetFlowService;

    /** 支出分类「投资」：只涉及资产↔投资划转，不计入支出统计（统计 SQL 已排除） */
    private static final String CATEGORY_INVEST = "投资";

    @Autowired
    private com.project.person.admin.ai.AiFacade aiFacade;

    /**
     * 识图线程池：多图并行调用 AI（一张图一个任务），wall time ≈ 最慢的一张。
     * 不采用「一次请求传多图」：kimi-k2.6 是推理模型，多图在同一会话里串行处理，
     * 延迟随张数线性增长，且一次解析失败会导致整批丢失。
     */
    private final java.util.concurrent.ExecutorService recognizePool =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "expense-recognize");
                t.setDaemon(true);
                return t;
            });

    @javax.annotation.PreDestroy
    void shutdownRecognizePool() {
        recognizePool.shutdown();
    }

    public PageResult<Expense> list(String month, int pageNum, int pageSize, String keyword) {
        YearMonth ym = IncomeService.parseMonth(month, true);
        Long userId = UserContext.getUserId();
        int offset = (pageNum - 1) * pageSize;
        List<Expense> list = expenseMapper.page(userId, ym.atDay(1), ym.plusMonths(1).atDay(1), keyword, pageSize, offset);
        long total = expenseMapper.count(userId, ym.atDay(1), ym.plusMonths(1).atDay(1), keyword);
        return new PageResult<>(list, total);
    }

    /** 新增支出：支付资产金额相应减少（检查余额） */
    @Transactional
    public Expense create(Expense expense) {
        checkExpense(expense);
        Long userId = UserContext.getUserId();
        Asset asset = assetSupport.requireCashAsset(userId, expense.getAssetId());
        assetSupport.checkBalance(asset, expense.getAmount());
        expense.setId(null);
        expense.setUserId(userId);
        expense.setRemark(expense.getRemark() == null ? "" : expense.getRemark());
        expenseMapper.insert(expense);
        assetMapper.adjustValue(asset.getId(), userId, expense.getAmount().negate());
        assetFlowService.record(userId, asset.getId(), AssetFlowService.TYPE_EXPENSE,
                expense.getAmount().negate(), expense.getRecordDate(),
                AssetFlowService.REF_EXPENSE, expense.getId(), expense.getCategory());
        // 类型为「投资」：金额从资产转入投资（渠道同步来的投资不产生变化，由渠道侧同步）
        applyInvestLink(userId, expense);
        snapshotService.recordToday(userId);
        return expense;
    }

    /** 修改支出：先回滚旧资产，再应用到新资产 */
    @Transactional
    public Expense update(Long id, Expense expense) {
        checkExpense(expense);
        Long userId = UserContext.getUserId();
        Expense old = expenseMapper.findById(id, userId);
        if (old == null) {
            throw BizException.badRequest("记录不存在或不属于当前用户");
        }
        Asset asset = assetSupport.requireCashAsset(userId, expense.getAssetId());
        // 同一资产时净调整 = 新金额 - 旧金额（支出增加需补扣，检查余额）
        if (old.getAssetId() != null && old.getAssetId().equals(asset.getId())) {
            BigDecimal delta = expense.getAmount().subtract(old.getAmount());
            if (delta.compareTo(BigDecimal.ZERO) > 0) {
                assetSupport.checkBalance(asset, delta);
                assetMapper.adjustValue(asset.getId(), userId, delta.negate());
            } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
                assetMapper.adjustValue(asset.getId(), userId, delta.negate());
            }
        } else {
            if (old.getAssetId() != null) {
                assetMapper.adjustValue(old.getAssetId(), userId, old.getAmount());
            }
            assetSupport.checkBalance(asset, expense.getAmount());
            assetMapper.adjustValue(asset.getId(), userId, expense.getAmount().negate());
        }
        // 先回滚旧记录的投资联动（旧记录若是投资型且当时联动过），再按新记录重新联动
        reverseInvestLink(userId, old);
        // 资产变动流水回滚旧记录，保存后按新记录补记
        assetFlowService.rollback(userId, AssetFlowService.REF_EXPENSE, id);
        expense.setId(id);
        expense.setUserId(userId);
        if (expenseMapper.update(expense) == 0) {
            throw BizException.badRequest("记录不存在或不属于当前用户");
        }
        assetFlowService.record(userId, asset.getId(), AssetFlowService.TYPE_EXPENSE,
                expense.getAmount().negate(), expense.getRecordDate(),
                AssetFlowService.REF_EXPENSE, id, expense.getCategory());
        applyInvestLink(userId, expense);
        snapshotService.recordToday(userId);
        return expense;
    }

    /** 删除支出：资产金额相应回增 */
    @Transactional
    public void delete(Long id) {
        Long userId = UserContext.getUserId();
        Expense old = expenseMapper.findById(id, userId);
        if (old == null) {
            throw BizException.badRequest("记录不存在或不属于当前用户");
        }
        if (old.getAssetId() != null && old.getAmount() != null) {
            assetMapper.adjustValue(old.getAssetId(), userId, old.getAmount());
        }
        reverseInvestLink(userId, old);
        assetFlowService.rollback(userId, AssetFlowService.REF_EXPENSE, id);
        expenseMapper.delete(id, userId);
        snapshotService.recordToday(userId);
    }

    /**
     * 支出类型为「投资」且关联了投资标的时：支出金额（已从资产扣出）转入该投资的投入与市值。
     * 渠道同步来的投资（sourceChannel 非空）不产生变化——其变化由渠道侧同步产生。
     */
    private void applyInvestLink(Long userId, Expense expense) {
        if (expense == null || !CATEGORY_INVEST.equals(expense.getCategory())
                || expense.getInvestmentId() == null || expense.getAmount() == null) {
            return;
        }
        Investment inv = investmentMapper.findById(expense.getInvestmentId(), userId);
        if (inv == null) {
            throw BizException.badRequest("关联的投资标的不存在或不属于当前用户");
        }
        if (StringUtils.hasText(inv.getSourceChannel())) {
            log.info("支出-投资联动跳过：投资 [{}] 来自渠道（{}）同步，变化由渠道侧产生",
                    inv.getName(), inv.getSourceChannel());
            return;
        }
        investmentMapper.adjustValue(inv.getId(), userId, expense.getAmount());
        // 记台账：支出转入投资的投入金额是台账的一部分，amount 由台账净额统一维护
        investmentService.recordAmountTrade(userId, inv.getId(), expense.getAmount(), expense.getRecordDate());
    }

    /** 回滚旧支出记录的投资联动（编辑/删除时调用；渠道同步投资从未联动过，天然跳过） */
    private void reverseInvestLink(Long userId, Expense old) {
        if (old == null || !CATEGORY_INVEST.equals(old.getCategory())
                || old.getInvestmentId() == null || old.getAmount() == null) {
            return;
        }
        Investment inv = investmentMapper.findById(old.getInvestmentId(), userId);
        if (inv == null) {
            // 投资已被删除（连同历史清理），无从回滚，跳过
            return;
        }
        if (StringUtils.hasText(inv.getSourceChannel())) {
            return;
        }
        investmentMapper.adjustValue(inv.getId(), userId, old.getAmount().negate());
        // 回滚台账：对应的正向 BUY 台账抵销（净额回落）
        investmentService.recordAmountTrade(userId, inv.getId(), old.getAmount().negate(), old.getRecordDate());
    }

    /**
     * AI 识图批量解析支出（图片不保存，实时调 AI）。
     * 多图并行（线程池，一张图一个任务），单图失败以 error 占位不影响其他图；
     * 一张图可识别出多笔（模型输出数组时逐笔展开），每笔渠道自动匹配用户现金/银行卡资产。
     * 结果不落库、不扣款，由用户确认后走正常创建流程。
     */
    public List<com.project.person.admin.model.RecognizedExpense> recognize(
            org.springframework.web.multipart.MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw BizException.badRequest("请上传至少一张图片");
        }
        if (files.length > 9) {
            throw BizException.badRequest("一次最多识别 9 张图片");
        }
        Long userId = UserContext.getUserId();
        List<Asset> cashAssets = cashAssets(userId);
        String prompt = assetPrompt(cashAssets);
        // 并行识别，按上传顺序汇总结果
        List<java.util.concurrent.Future<List<com.project.person.admin.model.RecognizedExpense>>> futures =
                new java.util.ArrayList<>();
        for (org.springframework.web.multipart.MultipartFile file : files) {
            futures.add(recognizePool.submit(() -> recognizeOne(file, prompt, cashAssets)));
        }
        return collectFutures(futures);
    }

    /** 候选支付资产（现金/银行卡） */
    private List<Asset> cashAssets(Long userId) {
        List<Asset> cashAssets = new java.util.ArrayList<>();
        for (Asset a : assetMapper.list(userId)) {
            if ("现金".equals(a.getCategory()) || "银行卡".equals(a.getCategory())) {
                cashAssets.add(a);
            }
        }
        return cashAssets;
    }

    /** 候选支付资产名单拼进 prompt，让模型按用户已有资产输出渠道词 */
    private String assetPrompt(List<Asset> cashAssets) {
        StringBuilder prompt = new StringBuilder();
        if (!cashAssets.isEmpty()) {
            prompt.append("用户已有的支付资产（assetHint 请从中选择最接近的名称）：");
            for (Asset a : cashAssets) {
                prompt.append(a.getName()).append('、');
            }
            prompt.setLength(prompt.length() - 1);
            prompt.append("。");
        }
        return prompt.toString();
    }

    /**
     * OCR 文本批量解析支出（前端本地 OCR 预提取，AI 只收纯文本，更快更省）。
     * 一段文本对应一张图，可多笔；解析口径与识图一致，结果同样不落库不扣款。
     */
    public List<com.project.person.admin.model.RecognizedExpense> recognizeFromTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw BizException.badRequest("没有可识别的文字内容");
        }
        if (texts.size() > 9) {
            throw BizException.badRequest("一次最多识别 9 张图片");
        }
        Long userId = UserContext.getUserId();
        List<Asset> cashAssets = cashAssets(userId);
        String prompt = assetPrompt(cashAssets);
        List<java.util.concurrent.Future<List<com.project.person.admin.model.RecognizedExpense>>> futures =
                new java.util.ArrayList<>();
        for (String text : texts) {
            futures.add(recognizePool.submit(() -> recognizeOneText(text, prompt, cashAssets)));
        }
        return collectFutures(futures);
    }

    /** 汇总并行识别结果：单任务失败以 error 占位不影响其他 */
    private List<com.project.person.admin.model.RecognizedExpense> collectFutures(
            List<java.util.concurrent.Future<List<com.project.person.admin.model.RecognizedExpense>>> futures) {
        List<com.project.person.admin.model.RecognizedExpense> results = new java.util.ArrayList<>();
        for (java.util.concurrent.Future<List<com.project.person.admin.model.RecognizedExpense>> future : futures) {
            try {
                results.addAll(future.get());
            } catch (Exception e) {
                log.warn("支出识别任务失败: {}", e.getMessage());
                com.project.person.admin.model.RecognizedExpense r =
                        new com.project.person.admin.model.RecognizedExpense();
                r.setError("识别失败：" + e.getMessage());
                results.add(r);
            }
        }
        return results;
    }

    /** 单段 OCR 文本识别：文本可能含多笔，模型输出数组时逐笔展开 */
    private List<com.project.person.admin.model.RecognizedExpense> recognizeOneText(
            String text, String prompt, List<Asset> cashAssets) {
        List<com.project.person.admin.model.RecognizedExpense> out = new java.util.ArrayList<>();
        com.project.person.admin.model.RecognizedExpense first = new com.project.person.admin.model.RecognizedExpense();
        out.add(first);
        try {
            String userPrompt = "以下是支付截图的本地 OCR 提取文本（可能有少量错别字/乱码，请结合上下文理解，"
                    + "按行区分多笔消费；时间与金额以文本中最合理的一次支付为准）：\n" + text
                    + (prompt == null || prompt.isEmpty() ? "" : "\n" + prompt);
            String resp = aiFacade.chat(com.project.person.admin.ai.AiAbility.ANALYZE_EXPEND_BY_PIC,
                    userPrompt, null);
            fillRecognized(out, first, resp, cashAssets);
        } catch (BizException e) {
            first.setError(e.getMessage());
        } catch (Exception e) {
            log.warn("支出文本识别失败: {}", e.getMessage());
            first.setError("识别失败：" + e.getMessage());
        }
        return out;
    }

    /** 单图识别：返回 1~N 条记录（图中有多笔支付时逐笔展开），异常兜底为单条 error 占位 */
    private List<com.project.person.admin.model.RecognizedExpense> recognizeOne(
            org.springframework.web.multipart.MultipartFile file, String prompt, List<Asset> cashAssets) {
        List<com.project.person.admin.model.RecognizedExpense> out = new java.util.ArrayList<>();
        com.project.person.admin.model.RecognizedExpense first = new com.project.person.admin.model.RecognizedExpense();
        out.add(first);
        try {
            if (file.isEmpty()) {
                first.setError("空文件");
                return out;
            }
            String mime = file.getContentType();
            if (mime == null || !mime.startsWith("image/")) {
                mime = "image/jpeg";
            }
            /* 先压缩再 base64：手机原图单张 5~12MB，9 张并发转 base64 峰值可达 400MB+，
               512M 堆直接 OOM。统一压到长边 1280 的 JPEG（通常 <300KB），峰值降到几 MB，
               且 AI 传输更快、识别不受影响。 */
            byte[] compact = com.project.person.admin.common.ImageCompactor.compact(file.getBytes());
            String dataUrl = "data:image/jpeg;base64,"
                    + java.util.Base64.getEncoder().encodeToString(compact);
            String resp = aiFacade.chat(com.project.person.admin.ai.AiAbility.ANALYZE_EXPEND_BY_PIC,
                    prompt, java.util.Collections.singletonList(dataUrl));
            fillRecognized(out, first, resp, cashAssets);
        } catch (BizException e) {
            first.setError(e.getMessage());
        } catch (Exception e) {
            log.warn("支出图片识别失败: {}", e.getMessage());
            first.setError("识别失败：" + e.getMessage());
        }
        return out;
    }

    /**
     * 解析模型输出（容错提取首个 JSON 块，对象或数组均可），并把支付渠道匹配到用户资产。
     * 模型输出数组（图内多笔）时逐笔展开进 out；整图无法识别时 out 保留单条 error。
     */
    private void fillRecognized(List<com.project.person.admin.model.RecognizedExpense> out,
                                com.project.person.admin.model.RecognizedExpense first,
                                String resp, List<Asset> cashAssets) {
        String json = resp;
        int objStart = resp.indexOf('{');
        int arrStart = resp.indexOf('[');
        int start;
        if (objStart >= 0 && arrStart >= 0) {
            start = Math.min(objStart, arrStart);
        } else {
            start = Math.max(objStart, arrStart);
        }
        if (start >= 0) {
            char close = resp.charAt(start) == '{' ? '}' : ']';
            int end = resp.lastIndexOf(close);
            if (end > start) {
                json = resp.substring(start, end + 1);
            }
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (node.isArray()) {
                // 图内多笔：逐笔展开；空数组视为未识别
                out.clear();
                if (node.size() == 0) {
                    first.setError("未识别出消费记录");
                    out.add(first);
                    return;
                }
                for (com.fasterxml.jackson.databind.JsonNode item : node) {
                    com.project.person.admin.model.RecognizedExpense r =
                            new com.project.person.admin.model.RecognizedExpense();
                    fillOne(r, item, cashAssets);
                    out.add(r);
                }
            } else {
                fillOne(first, node, cashAssets);
            }
        } catch (Exception e) {
            first.setError("结果解析失败：" + e.getMessage());
        }
    }

    /** 解析单条记录并匹配支付资产 */
    private void fillOne(com.project.person.admin.model.RecognizedExpense r,
                         com.fasterxml.jackson.databind.JsonNode node, List<Asset> cashAssets) {
        if (node.hasNonNull("error")) {
            r.setError(node.path("error").asText());
            return;
        }
        String amountText = node.path("amount").asText(null);
        if (amountText != null) {
            r.setAmount(new BigDecimal(amountText
                    .replace(",", "").replace("¥", "").replace("￥", "").trim()));
        }
        String dateText = node.path("recordDate").asText(null);
        if (dateText != null && !dateText.isEmpty() && !"null".equalsIgnoreCase(dateText)) {
            r.setRecordDate(java.time.LocalDate.parse(dateText.trim().substring(0, 10)));
        }
        String category = node.path("category").asText(null);
        r.setCategory(category == null ? "其他" : category.trim());
        String hint = node.path("assetHint").asText(null);
        r.setAssetHint(hint);
        r.setRemark(node.path("remark").asText(""));
        Asset matched = matchAsset(hint, cashAssets);
        if (matched != null) {
            r.setAssetId(matched.getId());
            r.setAssetName(matched.getName());
        }
    }

    /** 支付渠道 → 用户资产模糊匹配（去空格后双向包含，命中名称更短更精确者优先） */
    private Asset matchAsset(String hint, List<Asset> cashAssets) {
        if (!StringUtils.hasText(hint)) {
            return null;
        }
        String h = hint.replaceAll("\\s", "");
        Asset best = null;
        for (Asset a : cashAssets) {
            String name = a.getName().replaceAll("\\s", "");
            if (name.isEmpty()) {
                continue;
            }
            if (h.contains(name) || name.contains(h)) {
                if (best == null
                        || name.length() < best.getName().replaceAll("\\s", "").length()) {
                    best = a;
                }
            }
        }
        return best;
    }

    private void checkExpense(Expense expense) {
        if (expense == null || expense.getAmount() == null || expense.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.badRequest("金额必须大于 0");
        }
        if (!StringUtils.hasText(expense.getCategory())) {
            throw BizException.badRequest("分类不能为空");
        }
        if (expense.getRecordDate() == null) {
            throw BizException.badRequest("支出日期不能为空");
        }
    }
}
