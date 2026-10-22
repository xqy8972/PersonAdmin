package com.project.person.admin.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 调用门面（对标缓存的 CacheFacade）：业务代码只依赖本类。
 * 后续更换模型/厂商，只需改下面 @Qualifier 指向的实现（或做成配置项切换）。
 */
@Service
public class AiFacade {

    private static final Logger log = LoggerFactory.getLogger(AiFacade.class);

    @Autowired
    @Qualifier("kimiAiImpl")
    private AiInterface ai;

    /**
     * 调用指定 AI 能力。prompt 模板由能力枚举对应的 md 文件提供（classpath 相对路径）。
     */
    public String chat(AiAbility ability, String userPrompt, List<String> images) {
        log.debug("AI 能力调用 [{}] via {}, images={}", ability.name(), ai.name(),
                images == null ? 0 : images.size());
        return ai.chat(ability, userPrompt, images);
    }

    /**
     * 多轮对话调用（首轮/修正轮共用）：turns 为完整历史（含图片），由实现组装上下文。
     */
    public String chat(AiAbility ability, List<AiChatTurn> turns) {
        log.debug("AI 多轮对话 [{}] via {}, turns={}", ability.name(), ai.name(),
                turns == null ? 0 : turns.size());
        return ai.chat(ability, turns);
    }
}
