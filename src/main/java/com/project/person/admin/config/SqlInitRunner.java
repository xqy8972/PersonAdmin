package com.project.person.admin.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 初始数据导入：schema.sql 由 spring.sql.init 在 Bean 初始化前建好（幂等），
 * 这里仅当 sys_user 为空（全新库）时执行 data.sql，
 * 避免 H2 文件库重启后 mock 数据重复插入。
 * 必须早于 DataInitializer（@Order(1) < @Order(2)），否则默认用户先建会导致误判为非空库。
 */
@Component
@Order(1)
public class SqlInitRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SqlInitRunner.class);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) throws Exception {
        Long userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user", Long.class);
        if (userCount == null || userCount == 0) {
            try (Connection conn = dataSource.getConnection()) {
                // 必须显式指定 UTF-8：ScriptUtils 默认用平台编码（Windows GBK），会把中文 mock 数据写乱码
                ScriptUtils.executeSqlScript(conn, new EncodedResource(
                        new ClassPathResource("data.sql"), java.nio.charset.StandardCharsets.UTF_8));
            }
            log.info("全新数据库，已导入初始示例数据（data.sql）");
        } else {
            log.info("数据库已有数据，跳过 data.sql（共 {} 个用户）", userCount);
        }
    }
}
