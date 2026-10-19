package com.project.person.admin.config;

import com.project.person.admin.mapper.SysUserMapper;
import com.project.person.admin.model.SysUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动初始化：默认用户不存在时自动创建（密码 BCrypt 现场加密）
 * 默认账号 xqy8972 / 123456。
 * 排在 SqlInitRunner 之后（@Order(2)），保证空库先灌 mock 数据再由本类占住 user_id=1。
 */
@Component
@Order(2)
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    public static final String DEFAULT_USERNAME = "xqy8972";
    public static final String DEFAULT_PASSWORD = "123456";
    public static final String DEFAULT_NICKNAME = "小q";

    @Autowired
    private SysUserMapper sysUserMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public void run(String... args) {
        if (sysUserMapper.findByUsername(DEFAULT_USERNAME) == null) {
            SysUser user = new SysUser();
            user.setUsername(DEFAULT_USERNAME);
            user.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
            user.setNickname(DEFAULT_NICKNAME);
            sysUserMapper.insert(user);
            log.info("已创建默认用户: {} / {}", DEFAULT_USERNAME, DEFAULT_PASSWORD);
        }
    }
}
