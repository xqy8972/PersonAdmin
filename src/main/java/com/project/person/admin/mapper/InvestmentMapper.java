package com.project.person.admin.mapper;

import com.project.person.admin.model.Investment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface InvestmentMapper {

    @Select("SELECT * FROM investment WHERE user_id = #{userId} ORDER BY buy_date DESC, id DESC")
    List<Investment> list(@Param("userId") Long userId);

    @Select("SELECT * FROM investment WHERE id = #{id} AND user_id = #{userId}")
    Investment findById(@Param("id") Long id, @Param("userId") Long userId);

    /** 按行情代码找投资（外部渠道同步对账用） */
    @Select("SELECT * FROM investment WHERE user_id = #{userId} AND code = #{code}")
    Investment findByCode(@Param("userId") Long userId, @Param("code") String code);

    @Insert("INSERT INTO investment (user_id, name, type, code, amount, current_value, shares, source_asset_id, source_channel, fee_rate, fee_fixed, buy_date, remark) " +
            "VALUES (#{userId}, #{name}, #{type}, #{code}, #{amount}, #{currentValue}, #{shares}, #{sourceAssetId}, #{sourceChannel}, COALESCE(#{feeRate}, 0), COALESCE(#{feeFixed}, 0), #{buyDate}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Investment investment);

    @Update("UPDATE investment SET name = #{name}, type = #{type}, code = #{code}, amount = #{amount}, " +
            "current_value = #{currentValue}, shares = #{shares}, source_asset_id = #{sourceAssetId}, " +
            "source_channel = #{sourceChannel}, fee_rate = COALESCE(#{feeRate}, 0), fee_fixed = COALESCE(#{feeFixed}, 0), buy_date = #{buyDate}, remark = #{remark} WHERE id = #{id} AND user_id = #{userId}")
    int update(Investment investment);

    /** 投入金额与当前市值同额增减（支出-投资联动用，delta 为人民币元） */
    @Update("UPDATE investment SET amount = amount + #{delta}, current_value = current_value + #{delta} " +
            "WHERE id = #{id} AND user_id = #{userId}")
    int adjustValue(@Param("id") Long id, @Param("userId") Long userId, @Param("delta") BigDecimal delta);

    @Delete("DELETE FROM investment WHERE id = #{id} AND user_id = #{userId}")
    int delete(@Param("id") Long id, @Param("userId") Long userId);

    /** 所有用户填过行情代码的标的（去重），用于行情缓存初始化与定时拉取 */
    @Select("SELECT DISTINCT code FROM investment WHERE code IS NOT NULL AND code <> ''")
    List<String> listDistinctCodes();

    /** 所有用户填过行情代码的投资记录（收盘落库时遍历更新市值用） */
    @Select("SELECT * FROM investment WHERE code IS NOT NULL AND code <> ''")
    List<Investment> listAllWithCode();

    /** 全量投资（交易记录存量迁移用） */
    @Select("SELECT * FROM investment")
    List<Investment> listAll();

    @Select("SELECT IFNULL(SUM(current_value), 0) FROM investment WHERE user_id = #{userId}")
    BigDecimal sumCurrentValue(@Param("userId") Long userId);

    /** 直接设置当前市值（写入市值快照时用） */
    @Update("UPDATE investment SET current_value = #{value} WHERE id = #{id} AND user_id = #{userId}")
    int updateCurrentValue(@Param("id") Long id, @Param("userId") Long userId, @Param("value") BigDecimal value);
}
