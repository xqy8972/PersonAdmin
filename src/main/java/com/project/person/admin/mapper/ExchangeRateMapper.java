package com.project.person.admin.mapper;

import com.project.person.admin.model.ExchangeRate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ExchangeRateMapper {

    /** 按 (currency, rate_date) 唯一键 upsert（MySQL 语法；切回 H2 需改回 MERGE INTO ... KEY(...)） */
    @Insert("INSERT INTO exchange_rate (currency, rate, rate_date) " +
            "VALUES (#{currency}, #{rate}, #{rateDate}) AS new " +
            "ON DUPLICATE KEY UPDATE rate = new.rate")
    int upsert(ExchangeRate rate);

    /** 某币种最近一条历史汇率（缓存未命中时兜底） */
    @Select("SELECT * FROM exchange_rate WHERE currency = #{currency} ORDER BY rate_date DESC LIMIT 1")
    ExchangeRate findLatest(@Param("currency") String currency);

    /** 不晚于指定日期的汇率记录数（启动检查 2025-01-01 起历史是否已回填用） */
    @Select("SELECT COUNT(*) FROM exchange_rate WHERE rate_date <= #{date}")
    int countOnOrBefore(@Param("date") LocalDate date);

    /** 某币种不晚于指定日期的最近一条历史汇率（补录历史买卖按当日汇率计算用） */
    @Select("SELECT * FROM exchange_rate WHERE currency = #{currency} AND rate_date <= #{date} " +
            "ORDER BY rate_date DESC LIMIT 1")
    ExchangeRate findOnOrBefore(@Param("currency") String currency, @Param("date") LocalDate date);

    /** 某币种不晚于指定日期的全部历史（升序，台账逐日重算按天取汇率用） */
    @Select("SELECT * FROM exchange_rate WHERE currency = #{currency} AND rate_date <= #{date} " +
            "ORDER BY rate_date ASC")
    List<ExchangeRate> listUpTo(@Param("currency") String currency, @Param("date") LocalDate date);
}
