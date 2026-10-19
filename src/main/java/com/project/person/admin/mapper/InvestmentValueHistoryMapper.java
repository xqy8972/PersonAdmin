package com.project.person.admin.mapper;

import com.project.person.admin.model.InvestmentValueHistory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface InvestmentValueHistoryMapper {

    @Select("SELECT * FROM investment_value_history " +
            "WHERE user_id = #{userId} AND investment_id = #{investmentId} " +
            "AND value_date >= #{start} AND value_date <= #{end} ORDER BY value_date ASC")
    List<InvestmentValueHistory> trend(@Param("userId") Long userId,
                                       @Param("investmentId") Long investmentId,
                                       @Param("start") LocalDate start,
                                       @Param("end") LocalDate end);

    @Insert("INSERT INTO investment_value_history (user_id, investment_id, value_date, market_value, price) " +
            "VALUES (#{userId}, #{investmentId}, #{valueDate}, #{marketValue}, #{price})")
    int insert(InvestmentValueHistory history);

    @Delete("DELETE FROM investment_value_history WHERE investment_id = #{investmentId} AND user_id = #{userId}")
    int deleteByInvestmentId(@Param("investmentId") Long investmentId, @Param("userId") Long userId);
}
