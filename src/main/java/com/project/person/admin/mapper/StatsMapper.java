package com.project.person.admin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 主页统计聚合查询（跨 income / expense 两表）
 */
@Mapper
public interface StatsMapper {

    /** 最近收支动态：UNION 后按日期倒序取前 8 条 */
    @Select("SELECT * FROM (" +
            "SELECT '收入' AS kind, amount, category, record_date FROM income " +
            "WHERE user_id = #{userId} AND record_date >= #{since} " +
            "UNION ALL " +
            "SELECT '支出' AS kind, amount, category, record_date FROM expense " +
            "WHERE user_id = #{userId} AND record_date >= #{since} AND category <> '投资'" +
            ") t ORDER BY record_date DESC LIMIT 8")
    List<Map<String, Object>> recentRecords(@Param("userId") Long userId, @Param("since") LocalDate since);
}
