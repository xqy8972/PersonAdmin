package com.project.person.admin.mapper;

import com.project.person.admin.model.MarketDailyQuote;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MarketDailyQuoteMapper {

    /** 按 (code, trade_date) 唯一键 upsert（MySQL 语法；切回 H2 需改回 MERGE INTO ... KEY(...)） */
    @Insert("INSERT INTO market_quote (code, name, type, trade_date, price) " +
            "VALUES (#{code}, #{name}, #{type}, #{tradeDate}, #{price}) AS new " +
            "ON DUPLICATE KEY UPDATE name = new.name, type = new.type, price = new.price")
    int upsert(MarketDailyQuote quote);

    /**
     * 多个代码各自最近 N 条收盘记录（market_quote 只在交易日落库，即天然交易日历），
     * 「最新盈亏」= 最近两个交易日收盘价差 × 持有数量。codes 为逗号分隔字符串。
     */
    @Select("SELECT * FROM (SELECT t.*, ROW_NUMBER() OVER (PARTITION BY code ORDER BY trade_date DESC) rn " +
            "FROM market_quote t WHERE code IN (${codes})) x WHERE rn <= #{limit} " +
            "ORDER BY code, trade_date DESC")
    List<MarketDailyQuote> listRecentByCodes(@Param("codes") String codes, @Param("limit") int limit);
}
