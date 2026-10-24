package com.project.person.admin.ai;

import java.util.List;

/**
 * AI 调用接口（对标缓存的 CacheInterface）：业务只依赖本接口与 AiFacade，
 * 替换模型（Kimi / 通义 / 智谱…）只需新增一个实现并在 AiFacade 中切换注入。
 */
public interface AiInterface {

    /**
     * 以指定能力（prompt 模板）完成一次多模态对话。
     *
     * @param ability   能力枚举，决定使用哪个 prompt 模板
     * @param userPrompt 用户侧补充信息（如候选资产名单），可为空串
     * @param images    base64 data-url 图片列表（data:image/jpeg;base64,...），可为空（纯文本对话）
     * @return 模型输出的文本（按 prompt 要求应为 JSON 等结构化内容）
     */
    String chat(AiAbility ability, String userPrompt, List<String> images);

    /**
     * 多轮对话（带历史上下文，可含图片）：首轮与修正轮共用本方法。
     * 无状态模型（如 kimi-k2.6）下由调用方把历史轮次（含图片）整体传入，
     * 实现负责按 role/text/images 组装 messages；有状态模型可忽略历史仅取末轮。
     */
    String chat(AiAbility ability, List<AiChatTurn> turns);

    /**
     * 流式多轮对话：模型边生成边通过 onDelta 吐出增量文本，方法返回完整结果。
     * 不支持流式的实现可保留默认抛异常，业务层回退 {@link #chat(AiAbility, List)}。
     */
    default String streamChat(AiAbility ability, List<AiChatTurn> turns,
                              java.util.function.Consumer<String> onDelta) {
        throw new UnsupportedOperationException(name() + " 不支持流式输出");
    }

    /**
     * 流式多轮对话（可临时覆盖模型）：modelOverride 非空时本调用使用该模型而非实现默认模型。
     * 不支持覆盖的实现忽略该参数。用于同一通道下「识图用 VL 模型、文字修正用文本快模型」。
     */
    default String streamChat(String modelOverride, AiAbility ability, List<AiChatTurn> turns,
                              java.util.function.Consumer<String> onDelta) {
        return streamChat(ability, turns, onDelta);
    }

    /** 实现标识（日志用），如 kimi */
    String name();
}
