package com.project.person.admin.mapper;

import com.project.person.admin.model.AssetFlow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AssetFlowMapper {

    @Insert("INSERT INTO asset_flow (user_id, asset_id, flow_type, amount, flow_date, ref_type, ref_id, remark) " +
            "VALUES (#{userId}, #{assetId}, #{flowType}, #{amount}, #{flowDate}, #{refType}, #{refId}, COALESCE(#{remark}, ''))")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AssetFlow flow);

    /** 按来源单据删除流水（编辑/删除单据时回滚对应资产变动记录） */
    @Delete("DELETE FROM asset_flow WHERE user_id = #{userId} AND ref_type = #{refType} AND ref_id = #{refId}")
    int deleteByRef(@Param("userId") Long userId, @Param("refType") String refType, @Param("refId") Long refId);

    /** 按资产删除全部流水（资产删除/卖出时连同历史一并清除） */
    @Delete("DELETE FROM asset_flow WHERE user_id = #{userId} AND asset_id = #{assetId}")
    int deleteByAsset(@Param("userId") Long userId, @Param("assetId") Long assetId);

    @Select("SELECT * FROM asset_flow WHERE asset_id = #{assetId} AND user_id = #{userId} ORDER BY flow_date DESC, id DESC")
    List<AssetFlow> listByAsset(@Param("assetId") Long assetId, @Param("userId") Long userId);
}
