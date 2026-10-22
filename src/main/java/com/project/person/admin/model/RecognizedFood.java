package com.project.person.admin.model;

/**
 * AI 识别出的一种食物（待用户确认后才落库）。food/amount/calories 可直接编辑；
 * needUserInput=true 表示 AI 识别为配料表/营养成分表、需要用户补充信息，question 为追问内容。
 */
public class RecognizedFood {

    private String food;
    /** 估算食用重量（克），无法估算为 null */
    private Double amount;
    /** 热量（千卡） */
    private Integer calories;
    /** 餐次：早餐/午餐/晚餐/加餐 */
    private String mealType;
    /** 是否需要用户补充信息（配料表/营养成分表场景） */
    private Boolean needUserInput;
    /** 向用户追问的内容（needUserInput 时有效） */
    private String question;
    /** 识别失败原因 */
    private String error;

    public String getFood() {
        return food;
    }

    public void setFood(String food) {
        this.food = food;
    }

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
    }

    public Integer getCalories() {
        return calories;
    }

    public void setCalories(Integer calories) {
        this.calories = calories;
    }

    public String getMealType() {
        return mealType;
    }

    public void setMealType(String mealType) {
        this.mealType = mealType;
    }

    public Boolean getNeedUserInput() {
        return needUserInput;
    }

    public void setNeedUserInput(Boolean needUserInput) {
        this.needUserInput = needUserInput;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
