package com.project.person.admin.mapper;

import com.project.person.admin.model.Asset;
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
public interface AssetMapper {

    @Select("SELECT * FROM asset WHERE user_id = #{userId} ORDER BY id ASC")
    List<Asset> list(@Param("userId") Long userId);

    @Select("SELECT * FROM asset WHERE id = #{id} AND user_id = #{userId}")
    Asset findById(@Param("id") Long id, @Param("userId") Long userId);

    @Insert("INSERT INTO asset (user_id, name, category, current_value, change_date, remark) " +
            "VALUES (#{userId}, #{name}, #{category}, #{currentValue}, #{changeDate}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Asset asset);

    @Update("UPDATE asset SET name = #{name}, category = #{category}, current_value = #{currentValue}, " +
            "remark = #{remark} WHERE id = #{id} AND user_id = #{userId}")
    int update(Asset asset);

    @Delete("DELETE FROM asset WHERE id = #{id} AND user_id = #{userId}")
    int delete(@Param("id") Long id, @Param("userId") Long userId);

    @Select("SELECT IFNULL(SUM(current_value), 0) FROM asset WHERE user_id = #{userId}")
    BigDecimal sumCurrentValue(@Param("userId") Long userId);

    /** 资产金额增减（delta 为正则增加，为负则减少），返回受影响行数 */
    @Update("UPDATE asset SET current_value = current_value + #{delta} WHERE id = #{id} AND user_id = #{userId}")
    int adjustValue(@Param("id") Long id, @Param("userId") Long userId, @Param("delta") BigDecimal delta);

    /** 直接设置资产金额（写入估值快照时用） */
    @Update("UPDATE asset SET current_value = #{value} WHERE id = #{id} AND user_id = #{userId}")
    int setValue(@Param("id") Long id, @Param("userId") Long userId, @Param("value") BigDecimal value);
}
