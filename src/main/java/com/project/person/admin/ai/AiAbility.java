package com.project.person.admin.ai;

/**
 * AI 能力枚举：每种能力对应一个 prompt 模板文件（classpath 相对路径，打 jar 在服务器上也能读）。
 * 新增能力 = 加一个枚举值 + 在 resources/ai/ 下加一个 md 文件，业务代码通过
 * {@link AiFacade#chat(AiAbility, String, java.util.List)} 调用，无需关心底层模型。
 */
public enum AiAbility {

    /** 解析支出图片（小票/支付截图）：识别支出时间、金额、支付渠道 */
    ANALYZE_EXPEND_BY_PIC("ai/AnalyzeExpendByPic.md");

    private final String promptPath;

    AiAbility(String promptPath) {
        this.promptPath = promptPath;
    }

    /** prompt 模板的 classpath 相对路径（相对于 resources 根） */
    public String getPromptPath() {
        return promptPath;
    }
}
