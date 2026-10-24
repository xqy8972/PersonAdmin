package com.project.person.admin.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 调用门面（对标缓存的 CacheFacade）：业务代码只依赖本类。
 * 通过 ai.vision.provider 配置切换实现：kimi（默认）或 openai-compat（SiliconFlow/百炼等，
 * 配置 ai.vision.base-url / api-key / model），换厂商业务零改动。
 */
@Service
public class AiFacade {

    private static final Logger log = LoggerFactory.getLogger(AiFacade.class);

    @Autowired
    @Qualifier("kimiAiImpl")
    private AiInterface kimi;

    @Autowired
    @Qualifier("openAiCompatAiImpl")
    private AiInterface openAiCompat;

    /** 视觉/对话模型提供方：kimi（默认）| openai-compat */
    @Value("${ai.vision.provider:kimi}")
    private String provider;

    private AiInterface current() {
        return "openai-compat".equalsIgnoreCase(provider) ? openAiCompat : kimi;
    }

    /** 当前生效的提供方标识（排查问题时日志用） */
    public String currentProvider() {
        AiInterface ai = current();
        return ai.name();
    }

    /**
     * 调用指定 AI 能力。prompt 模板由能力枚举对应的 md 文件提供（classpath 相对路径）。
     */
    public String chat(AiAbility ability, String userPrompt, List<String> images) {
        AiInterface ai = current();
        log.debug("AI 能力调用 [{}] via {}, images={}", ability.name(), ai.name(),
                images == null ? 0 : images.size());
        return ai.chat(ability, userPrompt, images);
    }

    /**
     * 多轮对话调用（首轮/修正轮共用）：turns 为完整历史（含图片），由实现组装上下文。
     */
    public String chat(AiAbility ability, List<AiChatTurn> turns) {
        AiInterface ai = current();
        log.debug("AI 多轮对话 [{}] via {}, turns={}", ability.name(), ai.name(),
                turns == null ? 0 : turns.size());
        return ai.chat(ability, turns);
    }

    /**
     * 流式多轮对话：模型边生成边把增量文本交给 onDelta（用于前端实时进度），
     * 返回值与非流式一致，为完整结果文本。实现不支持流式时由调用方回退 {@link #chat}。
     */
    public String streamChat(AiAbility ability, List<AiChatTurn> turns,
                             java.util.function.Consumer<String> onDelta) {
        AiInterface ai = current();
        log.debug("AI 流式对话 [{}] via {}, turns={}", ability.name(), ai.name(),
                turns == null ? 0 : turns.size());
        return ai.streamChat(ability, turns, onDelta);
    }
}
