package com.project.person.admin.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * prompt 模板加载器：按 {@link AiAbility} 的 classpath 相对路径读取 md 文件并缓存。
 * 模板打进 jar（resources/ai/*.md），本地与服务器均可读；md 修改后需重启生效。
 */
@Component
public class PromptTemplateLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateLoader.class);

    private final Map<AiAbility, String> cache = new EnumMap<>(AiAbility.class);

    /** 读取能力对应的 prompt 模板（首次加载后缓存） */
    public String load(AiAbility ability) {
        return cache.computeIfAbsent(ability, this::read);
    }

    private String read(AiAbility ability) {
        ClassPathResource res = new ClassPathResource(ability.getPromptPath());
        if (!res.exists()) {
            throw new IllegalStateException("AI prompt 模板不存在：" + ability.getPromptPath());
        }
        // 必须关闭流：Windows 下流不关闭会一直占用文件句柄，导致运行期间无法覆盖/删除该 md
        try (java.io.InputStream in = res.getInputStream()) {
            String content = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            log.info("AI prompt 模板已加载 [{}] <- {}", ability.name(), ability.getPromptPath());
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("AI prompt 模板读取失败：" + ability.getPromptPath(), e);
        }
    }
}
