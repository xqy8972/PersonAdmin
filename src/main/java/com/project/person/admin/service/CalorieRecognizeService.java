package com.project.person.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.person.admin.ai.AiAbility;
import com.project.person.admin.ai.AiChatTurn;
import com.project.person.admin.ai.AiFacade;
import com.project.person.admin.cache.CacheFacade;
import com.project.person.admin.common.BizException;
import com.project.person.admin.common.ImageCompactor;
import com.project.person.admin.common.UserContext;
import com.project.person.admin.model.RecognizedFood;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * AI 识别饮食（卡路里）：拍照（食物/配料表/营养成分表）或文字描述 → 识别食物清单，
 * 之后用户可用自然语言多轮修正（“那不是米饭，是面条”）。
 *
 * <p>上下文隔离：会话历史按 userId+convId 存缓存（key=chat:cal:{userId}:{convId}），
 * 绝不跨用户共享；图片不落盘只存于缓存会话中用于修正轮回看；读取时超过 2 小时视为过期。
 * 缓存只通过 CacheFacade 访问，换 Redis 实现时业务零改动。
 */
@Service
public class CalorieRecognizeService {

    private static final Logger log = LoggerFactory.getLogger(CalorieRecognizeService.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private static final String KEY_PREFIX = "chat:cal:";
    /** 会话空闲 2 小时后丢弃（避免缓存无限增长） */
    private static final long SESSION_TTL_MS = 2 * 60 * 60 * 1000L;
    /** 单会话最多保留的轮次数（user+assistant 算一轮），防止长对话无限膨胀 */
    private static final int MAX_TURNS_KEPT = 12;

    @Autowired
    private AiFacade aiFacade;

    @Autowired
    private CacheFacade cacheFacade;

    /** 识别返回：会话 ID + 食物清单 */
    public static class RecognizeResult {
        private String convId;
        private List<RecognizedFood> items;

        public RecognizeResult(String convId, List<RecognizedFood> items) {
            this.convId = convId;
            this.items = items;
        }

        public String getConvId() {
            return convId;
        }

        public List<RecognizedFood> getItems() {
            return items;
        }
    }

    /** 缓存中的会话状态：最近活跃时间 + 对话轮次（含图片，用于修正轮回看） */
    private static class ConvState {
        long lastActive;
        List<AiChatTurn> turns = new ArrayList<>();

        ConvState() {
            this.lastActive = System.currentTimeMillis();
        }
    }

    /**
     * 图片识别（≤9 张，单请求多图：同餐多道菜一次识别，延迟不随并行任务叠加）。
     * 返回会话 ID 与识别结果；不落库，用户确认修正后再走正常创建。
     */
    public RecognizeResult recognize(MultipartFile[] files) {
        return recognize(files, null);
    }

    /**
     * 图片识别（流式）：onDelta 非空时走 SSE 增量输出，边生成边把增量文本回调出去（实时进度）。
     */
    public RecognizeResult recognize(MultipartFile[] files, java.util.function.Consumer<String> onDelta) {
        if (files == null || files.length == 0) {
            throw BizException.badRequest("请选择要识别的图片");
        }
        if (files.length > 9) {
            throw BizException.badRequest("一次最多识别 9 张图片");
        }
        // 必须在请求线程内读完字节：流式场景控制器返回 SseEmitter 后请求即结束，
        // Tomcat 会清理临时上传文件，异步线程再读会失败
        List<byte[]> raws = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                if (!file.isEmpty()) {
                    raws.add(file.getBytes());
                }
            }
        } catch (Exception e) {
            throw BizException.badRequest("读取图片失败：" + e.getMessage());
        }
        return recognizeRaws(raws, onDelta);
    }

    /**
     * 图片字节识别（压缩→base64→AI）：流式/非流式共用入口；raws 须已脱离 multipart 生命周期。
     */
    public RecognizeResult recognizeRaws(List<byte[]> raws, java.util.function.Consumer<String> onDelta) {
        if (raws == null || raws.isEmpty()) {
            throw BizException.badRequest("请选择要识别的图片");
        }
        if (raws.size() > 9) {
            throw BizException.badRequest("一次最多识别 9 张图片");
        }
        List<String> images = new ArrayList<>();
        long totalBytes = 0;
        for (byte[] raw : raws) {
            /* 先压缩再 base64：手机原图单张 5~12MB，并发转 base64 峰值可达 400MB+，
               512M 堆直接 OOM。统一压到长边 1600 的 JPEG（通常 <500KB）。 */
            byte[] compact = ImageCompactor.compact(raw);
            totalBytes += compact.length;
            images.add("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(compact));
        }
        log.info("识图请求: {} 张图, 压缩后共 {}KB", raws.size(), totalBytes / 1024);
        String prompt = images.size() > 1
                ? "以下是 " + images.size() + " 张食物/营养成分相关图片（可能是同一餐的不同食物，也可能是配料表/营养成分表），请逐一识别："
                : "请识别这张图片中的食物或营养成分信息：";
        return runFirstTurn(prompt, images, onDelta);
    }

    /**
     * OCR 文本识别：前端本地 OCR 预提取（更快更省），一段文本对应一张图。
     */
    public RecognizeResult recognizeFromTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw BizException.badRequest("没有可识别的文字内容");
        }
        if (texts.size() > 9) {
            throw BizException.badRequest("一次最多识别 9 张图片");
        }
        StringBuilder sb = new StringBuilder("以下是从 ").append(texts.size())
                .append(" 张食物/营养成分图片中本地提取的 OCR 文本（可能有少量错别字/乱码，请结合上下文理解）：");
        for (int i = 0; i < texts.size(); i++) {
            sb.append("\n--- 第 ").append(i + 1).append(" 张 ---\n").append(texts.get(i));
        }
        return runFirstTurn(sb.toString(), null);
    }

    /**
     * 文字描述直接作为首轮（不走图片），如“午饭吃了一碗牛肉面加一个蛋”。
     */
    public RecognizeResult recognizeFromText(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw BizException.badRequest("请描述你吃的食物");
        }
        return runFirstTurn("我用文字描述我的饮食，请识别食物清单：\n" + text.trim(), null);
    }

    /**
     * 对话修正：把用户消息追加进会话历史，多轮调用 AI，返回修正后的全量结果。
     * convId 为 null/空时视为以纯文字开启新会话。
     */
    public RecognizeResult chat(String convId, String message) {
        return chat(convId, message, null);
    }

    /**
     * 对话修正（流式）：onDelta 非空时走 SSE 增量输出，增量文本实时回调给前端。
     */
    public RecognizeResult chat(String convId, String message,
                                java.util.function.Consumer<String> onDelta) {
        if (message == null || message.trim().isEmpty()) {
            throw BizException.badRequest("请输入要修改/补充的内容");
        }
        Long userId = UserContext.getUserId();
        ConvState state;
        String id = convId;
        if (id == null || id.trim().isEmpty()) {
            id = UUID.randomUUID().toString().replace("-", "");
            state = new ConvState();
        } else {
            state = loadState(userId, id);
        }
        state.turns.add(AiChatTurn.user(message.trim(), null));
        String resp;
        try {
            resp = callTextChat(state.turns, onDelta);
        } catch (Exception e) {
            // 失败的轮次不进历史，避免污染上下文
            state.turns.remove(state.turns.size() - 1);
            throw e;
        }
        List<RecognizedFood> items = parseItems(resp);
        state.turns.add(AiChatTurn.assistant(resp));
        trimTurns(state);
        state.lastActive = System.currentTimeMillis();
        cacheFacade.put(key(userId, id), state);
        log.info("饮食识别修正 conv={} userId={} 返回 {} 种食物", id, userId, items.size());
        return new RecognizeResult(id, items);
    }

    /** 首轮：建立新会话，user 轮（图片+文字）→ AI → assistant 轮入库 */
    private RecognizeResult runFirstTurn(String prompt, List<String> images) {
        return runFirstTurn(prompt, images, null);
    }

    private RecognizeResult runFirstTurn(String prompt, List<String> images,
                                         java.util.function.Consumer<String> onDelta) {
        Long userId = UserContext.getUserId();
        String id = UUID.randomUUID().toString().replace("-", "");
        ConvState state = new ConvState();
        state.turns.add(AiChatTurn.user(prompt, images));
        String resp;
        try {
            // 无图首轮（纯文字描述/追加识别后的汇总）走文本快模型，有图才走视觉模型
            resp = (images == null || images.isEmpty()) ? callTextChat(state.turns, onDelta)
                    : callAi(state.turns, onDelta);
        } catch (Exception e) {
            state.turns.remove(state.turns.size() - 1);
            throw e;
        }
        List<RecognizedFood> items = parseItems(resp);
        state.turns.add(AiChatTurn.assistant(resp));
        cacheFacade.put(key(userId, id), state);
        log.info("饮食识别首轮 conv={} userId={} images={} 返回 {} 种食物", id, userId,
                images == null ? 0 : images.size(), items.size());
        return new RecognizeResult(id, items);
    }

    /**
     * 竞速通道：与主通道（ai.vision.provider）并行的另一提供方（openai-compat | kimi）。
     * 两通道同时发请求，先成功返回者胜出——单平台高峰排队时由另一平台兜底，
     * 避免同平台重试等于重排同一队列。留空或与主通道相同则退化为单通道调用。
     */
    @Value("${ai.vision.race-provider:}")
    private String raceProvider;

    /**
     * 纯文字修正轮用的快模型（与视觉模型同通道同 key，只换模型；如 Qwen/Qwen3.5-35B-A3B）。
     * 修正轮不含新图片，把历史里的图片剥掉后走文本模型，速度远快于 VL 模型。留空则修正轮也用视觉模型。
     */
    @Value("${ai.text.model:}")
    private String textModel;

    private final java.util.concurrent.ExecutorService aiExec =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "ai-call");
                t.setDaemon(true);
                return t;
            });

    /**
     * 纯文字修正轮调用：历史中的图片剥掉（修正靠文字上下文，不必每轮重传图），
     * 配置 ai.text.model 时走文本快模型，速度远快于 VL 模型；未配置则回退视觉模型路径。
     */
    private String callTextChat(List<AiChatTurn> turns, java.util.function.Consumer<String> onDelta) {
        if (textModel == null || textModel.trim().isEmpty()) {
            return callAi(turns, onDelta);
        }
        List<AiChatTurn> textTurns = new ArrayList<>(turns.size());
        for (AiChatTurn t : turns) {
            textTurns.add(t.getImages() == null || t.getImages().isEmpty()
                    ? t : new AiChatTurn(t.getRole(), t.getText(), null));
        }
        long t0 = System.currentTimeMillis();
        try {
            return aiFacade.streamChatWithModel(textModel.trim(),
                    AiAbility.ANALYZE_CALORIE_BY_PIC, textTurns, onDelta);
        } finally {
            log.info("AI修正(文本模型 {}) 调用耗时 {}ms", textModel, System.currentTimeMillis() - t0);
        }
    }

    /** 统一走流式调用；配置了 race-provider 时双通道并行竞速（先成功胜出）。记录总耗时便于排查。 */
    private String callAi(List<AiChatTurn> turns, java.util.function.Consumer<String> onDelta) {        long t0 = System.currentTimeMillis();
        try {
            return race(turns, onDelta);
        } finally {
            log.info("AI识别调用耗时 {}ms, provider={}", System.currentTimeMillis() - t0,
                    aiFacade.currentProvider());
        }
    }

    private String race(List<AiChatTurn> turns, java.util.function.Consumer<String> onDelta) {
        String primary = aiFacade.currentProvider();
        String rival = raceProvider == null ? "" : raceProvider.trim();
        if (rival.isEmpty() || rival.equalsIgnoreCase(primary)) {
            return aiFacade.streamChat(AiAbility.ANALYZE_CALORIE_BY_PIC, turns, onDelta);
        }
        final String[] names = { primary, rival };
        final StringBuilder[] bufs = { new StringBuilder(), new StringBuilder() };
        @SuppressWarnings("unchecked")
        final java.util.concurrent.CompletableFuture<String>[] fs =
                new java.util.concurrent.CompletableFuture[2];
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            final long start = System.currentTimeMillis();
            java.util.function.Consumer<String> sink = d -> {
                synchronized (bufs[idx]) { bufs[idx].append(d); }
            };
            fs[i] = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> aiFacade.streamChatByProvider(names[idx],
                            AiAbility.ANALYZE_CALORIE_BY_PIC, turns, sink), aiExec)
                    .whenComplete((r, e) -> log.info("AI识别通道[{}] 耗时{}ms 结果={}", names[idx],
                            System.currentTimeMillis() - start,
                            e == null ? "胜出/完成" : "失败:" + e.getMessage()));
        }
        // 等任意一路完成；一路失败则继续等另一路，两路都失败抛最后一个错
        java.util.concurrent.LinkedBlockingQueue<java.util.concurrent.CompletableFuture<String>> done =
                new java.util.concurrent.LinkedBlockingQueue<>();
        for (java.util.concurrent.CompletableFuture<String> f : fs) {
            f.whenComplete((r, e) -> done.offer(f));
        }
        RuntimeException lastErr = null;
        for (int seen = 0; seen < 2; seen++) {
            java.util.concurrent.CompletableFuture<String> f;
            try {
                f = done.take();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
            try {
                String r = f.get();
                int idx = (f == fs[0]) ? 0 : 1;
                // 竞速期间不透增量（避免两路串流），胜出方完成后把缓冲增量一次性补发
                if (onDelta != null) {
                    String buffered;
                    synchronized (bufs[idx]) { buffered = bufs[idx].toString(); }
                    if (!buffered.isEmpty()) { onDelta.accept(buffered); }
                }
                return r;
            } catch (java.util.concurrent.ExecutionException ee) {
                Throwable c = ee.getCause() == null ? ee : ee.getCause();
                log.warn("AI识别通道调用失败，等待另一通道: {}", c.getMessage());
                lastErr = c instanceof RuntimeException ? (RuntimeException) c : new RuntimeException(c);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
        }
        throw lastErr == null ? new IllegalStateException("unreachable") : lastErr;
    }

    /** 读取并校验会话归属：key 带 userId，拿到别人的会话直接视为不存在，天然防越权 */
    private ConvState loadState(Long userId, String convId) {
        Object obj = cacheFacade.get(key(userId, convId));
        if (!(obj instanceof ConvState)) {
            throw BizException.badRequest("会话不存在或已过期（超过 2 小时），请重新拍照/描述");
        }
        ConvState state = (ConvState) obj;
        if (System.currentTimeMillis() - state.lastActive > SESSION_TTL_MS) {
            cacheFacade.remove(key(userId, convId));
            throw BizException.badRequest("会话已过期（超过 2 小时），请重新拍照/描述");
        }
        return state;
    }

    private void trimTurns(ConvState state) {
        // 始终保留首轮（含图片），裁掉更早的修正轮
        int maxMessages = MAX_TURNS_KEPT * 2;
        if (state.turns.size() > maxMessages) {
            state.turns = new ArrayList<>(state.turns.subList(
                    state.turns.size() - maxMessages, state.turns.size()));
            if (!"user".equals(state.turns.get(0).getRole())) {
                state.turns.remove(0);
            }
        }
    }

    private String key(Long userId, String convId) {
        return KEY_PREFIX + userId + ":" + convId;
    }

    /**
     * 解析模型输出（容错提取首个 JSON 块，对象或数组均可）：
     * 数组→逐条；对象→单条（error 或 question）。修正轮要求模型输出全量数组，这里同样兼容。
     */
    private List<RecognizedFood> parseItems(String resp) {
        List<RecognizedFood> out = new ArrayList<>();
        if (resp == null || resp.trim().isEmpty()) {
            RecognizedFood r = new RecognizedFood();
            r.setError("AI 未返回结果");
            out.add(r);
            return out;
        }
        String json = resp;
        int objStart = resp.indexOf('{');
        int arrStart = resp.indexOf('[');
        int start = (objStart >= 0 && arrStart >= 0) ? Math.min(objStart, arrStart)
                : Math.max(objStart, arrStart);
        if (start >= 0) {
            char close = resp.charAt(start) == '{' ? '}' : ']';
            int end = resp.lastIndexOf(close);
            if (end > start) {
                json = resp.substring(start, end + 1);
            }
        }
        try {
            JsonNode node = OM.readTree(json);
            if (node.isArray()) {
                if (node.size() == 0) {
                    RecognizedFood r = new RecognizedFood();
                    r.setError("未识别出食物");
                    out.add(r);
                    return out;
                }
                for (JsonNode item : node) {
                    out.add(fillOne(item));
                }
            } else {
                out.add(fillOne(node));
            }
        } catch (Exception e) {
            RecognizedFood r = new RecognizedFood();
            r.setError("结果解析失败：" + e.getMessage());
            out.add(r);
        }
        return out;
    }

    private RecognizedFood fillOne(JsonNode node) {
        RecognizedFood r = new RecognizedFood();
        if (node.hasNonNull("error")) {
            r.setError(node.path("error").asText());
            return r;
        }
        r.setFood(node.path("food").isNull() ? "" : node.path("food").asText("").trim());
        r.setAmount(node.hasNonNull("amount") && node.path("amount").isNumber()
                ? node.path("amount").asDouble() : null);
        r.setCalories(node.hasNonNull("calories") && node.path("calories").isNumber()
                ? (int) Math.round(node.path("calories").asDouble()) : null);
        r.setMealType(node.path("mealType").isNull() ? null : node.path("mealType").asText(null));
        boolean need = node.hasNonNull("question") && !node.path("question").isNull()
                && !node.path("question").asText().isEmpty();
        r.setNeedUserInput(need || Boolean.TRUE.equals(
                node.hasNonNull("needUserInput") ? node.path("needUserInput").asBoolean() : null));
        r.setQuestion(need ? node.path("question").asText() : null);
        return r;
    }

    /** 供 Controller/前端展示用：空结果判断 */
    public static boolean allFailed(List<RecognizedFood> items) {
        if (items == null || items.isEmpty()) {
            return true;
        }
        for (RecognizedFood f : items) {
            if (f.getError() == null) {
                return false;
            }
        }
        return true;
    }
}
