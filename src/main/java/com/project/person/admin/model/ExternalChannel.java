package com.project.person.admin.model;

import java.time.LocalDateTime;

/**
 * 外部渠道账户：用户手动添加（需填写并登记渠道账户名），同步数据推送进来时
 * 校验账户名匹配才允许落库。channel 预留多数据源扩展（THS=同花顺，后续可扩展其他渠道）。
 */
public class ExternalChannel {

    public static final String CHANNEL_THS = "THS";

    private Long id;
    private Long userId;
    /** 渠道标识：THS=同花顺（预留其他渠道） */
    private String channel;
    /** 渠道账户名（如 THS 登录用户名），同步推送时校验匹配 */
    private String accountName;
    /** ACTIVE / SYNC_ERROR / DISABLED */
    private String status;
    /** 推送凭证（添加渠道时生成，同步接口凭 X-Api-Key 头认证） */
    private String apiKey;
    private LocalDateTime lastSyncTime;
    private String lastSyncMsg;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public LocalDateTime getLastSyncTime() {
        return lastSyncTime;
    }

    public void setLastSyncTime(LocalDateTime lastSyncTime) {
        this.lastSyncTime = lastSyncTime;
    }

    public String getLastSyncMsg() {
        return lastSyncMsg;
    }

    public void setLastSyncMsg(String lastSyncMsg) {
        this.lastSyncMsg = lastSyncMsg;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
