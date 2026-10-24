package com.project.person.admin.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.project.person.admin.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * 通用 OpenAI 兼容多模态实现：对接任意兼容 /v1/chat/completions 的服务
 * （SiliconFlow、阿里百炼 compatible-mode、智谱、DeepSeek 等），用于替换慢速的
 * Moonshot 视觉接口（实测 Kimi k2.6 首 token 12~27s 波动，Qwen-VL 类通常 2~5s）。
 *
 * <p>配置项（ai.vision.*）：base-url（必填）、api-key（必填）、model（必填）。
 * 切换方式：环境变量 AI_VISION_PROVIDER=openai-compat 即可，业务代码零改动。
 */
@Service("openAiCompatAiImpl")
public class OpenAiCompatAiImpl implements AiInterface {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatAiImpl.class);

    @Value("${ai.vision.base-url:}")
    private String baseUrl;

    @Value("${ai.vision.api-key:}")
    private String apiKey;

    @Value("${ai.vision.model:}")
    private String model;

    /** 是否启用思考模式：Qwen3 系列在 SiliconFlow 默认开思考（实测同样请求 54s vs 关思考 2.5s），
        识图/修正场景不需要长推理，默认关闭（请求体带 enable_thinking=false） */
    @Value("${ai.thinking-enabled:false}")
    private boolean thinkingEnabled;

    @Autowired
    private PromptTemplateLoader promptLoader;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestTemplate restTemplate;

    public OpenAiCompatAiImpl() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(90_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /** 启动即打印当前生效的视觉模型配置，部署后看日志一眼确认跑的版本 */
    @javax.annotation.PostConstruct
    void logConfig() {
        log.info("AI视觉提供方: openai-compat, base-url={}, model={}", baseUrl, model);
    }

    @Override
    public String name() {
        return "openai-compat";
    }

    private void checkConfig() {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(apiKey) || !StringUtils.hasText(model)) {
            throw BizException.badRequest("视觉模型未配置完整（ai.vision.base-url / api-key / model）");
        }
    }

    private String url() {
        return baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
    }

    @Override
    public String chat(AiAbility ability, List<AiChatTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            throw BizException.badRequest("对话内容为空");
        }
        checkConfig();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey.trim());
            ResponseEntity<String> resp = restTemplate.exchange(
                    url(), HttpMethod.POST, new HttpEntity<>(buildBody(ability, turns, false), headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            String out = root.path("choices").path(0).path("message").path("content").asText(null);
            // 部分实现把多模态内容回成数组，取其中文本段
            if (!StringUtils.hasText(out) && root.path("choices").path(0).path("message").path("content").isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode p : root.path("choices").path(0).path("message").path("content")) {
                    if ("text".equals(p.path("type").asText())) {
                        sb.append(p.path("text").asText());
                    }
                }
                out = sb.toString();
            }
            if (!StringUtils.hasText(out)) {
                throw BizException.badRequest("AI 未返回识别结果");
            }
            return out;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("openai-compat 调用失败: {}", e.getMessage());
            throw BizException.badRequest("AI 识图失败：" + e.getMessage());
        }
    }

    @Override
    public String streamChat(AiAbility ability, List<AiChatTurn> turns,
                             java.util.function.Consumer<String> onDelta) {
        return streamChat(null, ability, turns, onDelta);
    }

    /** modelOverride 非空时临时换模型（如修正轮用文本快模型），其他与 {@link #streamChat} 一致 */
    @Override
    public String streamChat(String modelOverride, AiAbility ability, List<AiChatTurn> turns,
                             java.util.function.Consumer<String> onDelta) {
        if (turns == null || turns.isEmpty()) {
            throw BizException.badRequest("对话内容为空");
        }
        checkConfig();
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url()).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(120_000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(buildBody(modelOverride, ability, turns, true).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() >= 400) {
                String err = "";
                try (java.io.InputStream es = conn.getErrorStream()) {
                    if (es != null) {
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = es.read(buf)) > 0) {
                            bo.write(buf, 0, n);
                        }
                        err = new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
                log.warn("openai-compat 流式 HTTP 错误: status={}, body={}", conn.getResponseCode(), err);
                throw BizException.badRequest("AI 识图失败：HTTP " + conn.getResponseCode()
                        + extractErrDetail(err));
            }
            StringBuilder acc = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                    conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    JsonNode root = objectMapper.readTree(payload);
                    JsonNode choices = root.path("choices");
                    if (choices.size() == 0) {
                        continue;
                    }
                    JsonNode deltaNode = choices.get(0).path("delta").path("content");
                    // Qwen3-VL 等在流式下可能把 content 回成 [{type:text,text:...}] 数组，两种都兼容
                    StringBuilder chunk = new StringBuilder();
                    if (deltaNode.isTextual()) {
                        chunk.append(deltaNode.asText());
                    } else if (deltaNode.isArray()) {
                        for (JsonNode part : deltaNode) {
                            if (part.path("type").asText().equals("text")) {
                                chunk.append(part.path("text").asText());
                            }
                        }
                    }
                    if (chunk.length() > 0) {
                        String d = chunk.toString();
                        acc.append(d);
                        if (onDelta != null) {
                            onDelta.accept(d);
                        }
                    }
                }
            }
            if (acc.length() == 0) {
                throw BizException.badRequest("AI 未返回识别结果");
            }
            return acc.toString();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("openai-compat 流式调用失败: {}", e.getMessage());
            throw BizException.badRequest("AI 识图失败：" + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 从错误响应体提取 error.message，没有则返回空串 */
    private static String extractErrDetail(String errBody) {
        if (errBody == null || errBody.isEmpty()) {
            return "";
        }
        try {
            JsonNode n = new ObjectMapper().readTree(errBody);
            String m = n.path("error").path("message").asText(null);
            return m == null || m.isEmpty() ? "" : "（" + m + "）";
        } catch (Exception e) {
            return "";
        }
    }

    /** 组装 OpenAI 兼容请求体：system prompt + 多轮 messages（含图片） */
    private String buildBody(AiAbility ability, List<AiChatTurn> turns, boolean stream) {
        return buildBody(null, ability, turns, stream);
    }

    private String buildBody(String modelOverride, AiAbility ability, List<AiChatTurn> turns, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", StringUtils.hasText(modelOverride) ? modelOverride : model);
        if (!thinkingEnabled) {
            body.put("enable_thinking", false);
        }
        if (stream) {
            body.put("stream", true);
        }

        ArrayNode messages = body.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", promptLoader.load(ability));

        for (AiChatTurn turn : turns) {
            ObjectNode msg = messages.addObject();
            msg.put("role", turn.getRole());
            List<String> images = turn.getImages();
            boolean hasText = turn.getText() != null && !turn.getText().isEmpty();
            if (images == null || images.isEmpty()) {
                msg.put("content", hasText ? turn.getText() : "");
            } else {
                ArrayNode content = msg.putArray("content");
                for (String img : images) {
                    ObjectNode part = content.addObject();
                    part.put("type", "image_url");
                    part.putObject("image_url").put("url", img);
                }
                ObjectNode text = content.addObject();
                text.put("type", "text");
                text.put("text", hasText ? turn.getText() : " ");
            }
        }
        return body.toString();
    }

    @Override
    public String chat(AiAbility ability, String userPrompt, List<String> images) {
        checkConfig();
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            if (!thinkingEnabled) {
                body.put("enable_thinking", false);
            }

            ArrayNode messages = body.putArray("messages");
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", promptLoader.load(ability));

            ObjectNode user = messages.addObject();
            user.put("role", "user");
            ArrayNode content = user.putArray("content");
            if (images != null) {
                for (String img : images) {
                    ObjectNode part = content.addObject();
                    part.put("type", "image_url");
                    part.putObject("image_url").put("url", img);
                }
            }
            ObjectNode text = content.addObject();
            text.put("type", "text");
            text.put("text", userPrompt == null ? "" : userPrompt);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey.trim());
            ResponseEntity<String> resp = restTemplate.exchange(
                    url(), HttpMethod.POST, new HttpEntity<>(body.toString(), headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            String out = root.path("choices").path(0).path("message").path("content").asText(null);
            if (!StringUtils.hasText(out)) {
                throw BizException.badRequest("AI 未返回识别结果");
            }
            return out;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("openai-compat 调用失败: {}", e.getMessage());
            throw BizException.badRequest("AI 识图失败：" + e.getMessage());
        }
    }
}
