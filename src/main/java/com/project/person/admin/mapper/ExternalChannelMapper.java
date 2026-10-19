package com.project.person.admin.mapper;

import com.project.person.admin.model.ExternalChannel;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ExternalChannelMapper {

    @Select("SELECT * FROM external_channel WHERE user_id = #{userId} ORDER BY id ASC")
    List<ExternalChannel> listByUser(@Param("userId") Long userId);

    @Select("SELECT * FROM external_channel WHERE id = #{id} AND user_id = #{userId}")
    ExternalChannel findById(@Param("id") Long id, @Param("userId") Long userId);

    /** 按渠道 + 账户名定位（同步推送时校验用） */
    @Select("SELECT * FROM external_channel WHERE user_id = #{userId} AND channel = #{channel} AND account_name = #{accountName}")
    ExternalChannel findByAccount(@Param("userId") Long userId,
                                  @Param("channel") String channel,
                                  @Param("accountName") String accountName);

    /** 按推送凭证定位（X-Api-Key 认证） */
    @Select("SELECT * FROM external_channel WHERE api_key = #{apiKey}")
    ExternalChannel findByApiKey(@Param("apiKey") String apiKey);

    @Insert("INSERT INTO external_channel (user_id, channel, account_name, api_key, remark) " +
            "VALUES (#{userId}, #{channel}, #{accountName}, #{apiKey}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ExternalChannel channel);

    @Delete("DELETE FROM external_channel WHERE id = #{id} AND user_id = #{userId}")
    int delete(@Param("id") Long id, @Param("userId") Long userId);

    /** 同步完成后回写状态与时间 */
    @Update("UPDATE external_channel SET status = #{status}, last_sync_time = #{syncTime}, " +
            "last_sync_msg = #{msg}, update_time = #{syncTime} WHERE id = #{id}")
    int touchSync(@Param("id") Long id,
                  @Param("status") String status,
                  @Param("syncTime") LocalDateTime syncTime,
                  @Param("msg") String msg);
}
