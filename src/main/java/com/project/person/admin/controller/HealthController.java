package com.project.person.admin.controller;

import com.project.person.admin.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查接口：部署脚本通过 GET /personAdmin/health（prod 环境带 context-path）判断服务是否就绪。
 * 不走统一鉴权（SecurityConfig 放行），返回 200 即就绪。
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Result<String> health() {
        return Result.ok("OK");
    }
}
