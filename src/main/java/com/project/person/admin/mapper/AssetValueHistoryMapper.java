package com.project.person.admin.mapper;

import com.project.person.admin.model.AssetValueHistory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AssetValueHistoryMapper {

    /** 查询某用户在时间范围内的全部估值历史（按日期升序），趋势聚合在 Service 内完成 */
    @Select("SELECT * FROM asset_value_history " +
            "WHERE user_id = #{userId} AND value_date >= #{start} AND value_date <= #{end} " +
            "ORDER BY value_date ASC")
    List<AssetValueHistory> listByRange(@Param("userId") Long userId,
                                        @Param("start") LocalDate start,
                                        @Param("end") LocalDate end);

    @Insert("INSERT INTO asset_value_history (user_id, asset_id, value_date, asset_value) " +
            "VALUES (#{userId}, #{assetId}, #{valueDate}, #{assetValue})")
    int insert(AssetValueHistory history);

    @Delete("DELETE FROM asset_value_history WHERE asset_id = #{assetId} AND user_id = #{userId}")
    int deleteByAssetId(@Param("assetId") Long assetId, @Param("userId") Long userId);
}
