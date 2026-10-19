package com.project.person.admin.mapper;

import com.project.person.admin.model.InvestmentTrade;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InvestmentTradeMapper {

    @Insert("INSERT INTO investment_trade (user_id, investment_id, trade_type, trade_date, shares, price, amount, target_asset_id, remark) " +
            "VALUES (#{userId}, #{investmentId}, #{tradeType}, #{tradeDate}, #{shares}, #{price}, #{amount}, #{targetAssetId}, COALESCE(#{remark}, ''))")
    int insert(InvestmentTrade trade);

    @Select("SELECT * FROM investment_trade WHERE investment_id = #{investmentId} AND user_id = #{userId} ORDER BY trade_date ASC, id ASC")
    List<InvestmentTrade> listByInvestment(@Param("investmentId") Long investmentId, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM investment_trade WHERE investment_id = #{investmentId} AND user_id = #{userId}")
    int countByInvestment(@Param("investmentId") Long investmentId, @Param("userId") Long userId);

    @Delete("DELETE FROM investment_trade WHERE investment_id = #{investmentId} AND user_id = #{userId}")
    int deleteByInvestmentId(@Param("investmentId") Long investmentId, @Param("userId") Long userId);
}
