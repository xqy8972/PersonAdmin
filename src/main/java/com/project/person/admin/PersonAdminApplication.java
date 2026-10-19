package com.project.person.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@SpringBootApplication
@MapperScan("com.project.person.admin.mapper")
public class PersonAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(PersonAdminApplication.class, args);
    }
}
