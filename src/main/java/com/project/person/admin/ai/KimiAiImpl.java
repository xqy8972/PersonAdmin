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
 * Kimi（Moonshot AI）多模态实现：OpenAI 兼容接口，支持 base64 图片输入。
 * 配置项：moonshot.api-key（必填，环境变量 MOONSHOT_API_KEY 亦可）、moonshot.model（默认 kimi-k3）。
 * 注意不能用 kimi 前缀命名：Spring 宽松绑定会把 kimi.api-key 映射到环境变量 KIMI_API_KEY，
 * 本机 kimi 桌面端恰好注入了该变量（agent 网关 key），会导致误用而 401。
 * 换模型只需新增 AiInterface 的另一个实现并在 AiFacade 切换 @Qualifier。
 */
@Service("kimiAiImpl")
public class KimiAiImpl implements AiInterface {

    private static final Logger log = LoggerFactory.getLogger(KimiAiImpl.class);

    private static final String API_URL = "https://api.moonshot.cn/v1/chat/completions";

    @Value("${moonshot.api-key:}")
    private String apiKey;

    @Value("${moonshot.model:kimi-k3}")
    private String model;

    @Autowired
    private PromptTemplateLoader promptLoader;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 独立 RestTemplate（带超时），不复用行情用的 GBK 转换配置 */
    private final RestTemplate restTemplate;

    public KimiAiImpl() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public String name() {
        return "kimi";
    }

    @Override
    public String chat(AiAbility ability, List<AiChatTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            throw BizException.badRequest("对话内容为空");
        }
        if (!StringUtils.hasText(apiKey)) {
            throw BizException.badRequest("未配置 Kimi API Key（moonshot.api-key 或环境变量 MOONSHOT_API_KEY）");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey.trim());
            ResponseEntity<String> resp = restTemplate.exchange(
                    API_URL, HttpMethod.POST, new HttpEntity<>(buildBody(ability, turns, false), headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            String out = root.path("choices").path(0).path("message").path("content").asText(null);
            if (!StringUtils.hasText(out)) {
                throw BizException.badRequest("AI 未返回识别结果");
            }
            return out;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof org.springframework.web.client.HttpClientErrorException) {
                org.springframework.web.client.HttpClientErrorException he =
                        (org.springframework.web.client.HttpClientErrorException) e;
                log.warn("Kimi HTTP 错误: status={}, headers={}, body={}",
                        he.getStatusCode(), he.getResponseHeaders(), he.getResponseBodyAsString());
            }
            log.warn("Kimi 调用失败: {}", e.getMessage());
            throw BizException.badRequest("AI 识图失败：" + e.getMessage());
        }
    }

    @Override
    public String streamChat(AiAbility ability, List<AiChatTurn> turns,
                             java.util.function.Consumer<String> onDelta) {
        if (turns == null || turns.isEmpty()) {
            throw BizException.badRequest("对话内容为空");
        }
        if (!StringUtils.hasText(apiKey)) {
            throw BizException.badRequest("未配置 Kimi API Key（moonshot.api-key 或环境变量 MOONSHOT_API_KEY）");
        }
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(API_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            // 流式生成可能持续一两分钟，读取间隙不设 60s 短超时
            conn.setReadTimeout(300_000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(buildBody(ability, turns, true).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
                log.warn("Kimi 流式 HTTP 错误: status={}, body={}", conn.getResponseCode(), err);
                throw BizException.badRequest("AI 识图失败：HTTP " + conn.getResponseCode());
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
                    JsonNode delta = choices.get(0).path("delta").path("content");
                    if (delta.isTextual()) {
                        String d = delta.asText();
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
            log.warn("Kimi 流式调用失败: {}", e.getMessage());
            throw BizException.badRequest("AI 识图失败：" + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 组装 OpenAI 兼容请求体：system prompt + 多轮 messages（含图片），stream=true 时开启 SSE 增量输出 */
    private String buildBody(AiAbility ability, List<AiChatTurn> turns, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
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
        if (!StringUtils.hasText(apiKey)) {
            throw BizException.badRequest("未配置 Kimi API Key（moonshot.api-key 或环境变量 MOONSHOT_API_KEY）");
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            // 注意：kimi-k2.6 等推理模型仅允许 temperature=1，不显式传参，用模型默认值

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
                    API_URL, HttpMethod.POST, new HttpEntity<>(body.toString(), headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            String out = root.path("choices").path(0).path("message").path("content").asText(null);
            if (!StringUtils.hasText(out)) {
                throw BizException.badRequest("AI 未返回识别结果");
            }
            return out;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof org.springframework.web.client.HttpClientErrorException) {
                org.springframework.web.client.HttpClientErrorException he =
                        (org.springframework.web.client.HttpClientErrorException) e;
                log.warn("Kimi HTTP 错误: status={}, headers={}, body={}",
                        he.getStatusCode(), he.getResponseHeaders(), he.getResponseBodyAsString());
            }
            log.warn("Kimi 调用失败: {}", e.getMessage());
            throw BizException.badRequest("AI 识图失败：" + e.getMessage());
        }
    }
}
