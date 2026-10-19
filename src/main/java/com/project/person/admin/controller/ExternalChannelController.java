package com.project.person.admin.controller;

import com.project.person.admin.common.BizException;
import com.project.person.admin.common.Result;
import com.project.person.admin.model.ExternalChannel;
import com.project.person.admin.model.ExternalSyncRequest;
import com.project.person.admin.service.ExternalChannelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 外部渠道同步 /api/external：
 * 渠道管理（手动添加同花顺账户、列表、删除）+ 持仓推送入口（校验账户名后对账）。
 */
@RestController
@RequestMapping("/api/external")
public class ExternalChannelController {

    @Autowired
    private ExternalChannelService channelService;

    @GetMapping("/channel/list")
    public Result<List<ExternalChannel>> list() {
        try {
            return Result.ok(channelService.list());
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PostMapping("/channel")
    public Result<ExternalChannel> add(@RequestBody ExternalChannel channel) {
        try {
            return Result.ok(channelService.add(channel));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @DeleteMapping("/channel/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        try {
            channelService.delete(id);
            return Result.ok();
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /**
     * 持仓推送：由辅助电脑上的推送代理（ths_push_agent.py）调用。
     * 不走登录态，凭 X-Api-Key（添加渠道时生成）认证，且请求的渠道/账户名必须与登记一致。
     */
    @PostMapping("/sync")
    public Result<String> sync(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                               @RequestBody ExternalSyncRequest req) {
        try {
            return Result.ok(channelService.sync(apiKey, req));
        } catch (BizException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }
}
