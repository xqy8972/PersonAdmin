package com.project.person.admin.ai;

import java.util.List;

/**
 * 一轮对话（多轮场景用）：role 为 user / assistant，text 为文本内容，
 * images 为该轮的 base64 data-url 图片（data:image/jpeg;base64,...，可空）。
 * 业务层把历史轮次组装成 List&lt;AiChatTurn&gt; 交给 {@link AiInterface#chat(AiAbility, List)}，
 * 即可在无状态模型上实现带图片上下文的多轮修正。
 */
public class AiChatTurn {

    private String role;
    private String text;
    private List<String> images;

    public AiChatTurn() {
    }

    public AiChatTurn(String role, String text, List<String> images) {
        this.role = role;
        this.text = text;
        this.images = images;
    }

    public static AiChatTurn user(String text, List<String> images) {
        return new AiChatTurn("user", text, images);
    }

    public static AiChatTurn assistant(String text) {
        return new AiChatTurn("assistant", text, null);
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<String> getImages() {
        return images;
    }

    public void setImages(List<String> images) {
        this.images = images;
    }
}
