package com.project.person.admin.mapper;

import com.project.person.admin.model.BodyWeight;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface BodyWeightMapper {

    @Select("<script>" +
            "SELECT * FROM body_weight WHERE user_id = #{userId} " +
            "<if test='start != null'>AND record_date &gt;= #{start} </if>" +
            "<if test='end != null'>AND record_date &lt;= #{end} </if>" +
            "ORDER BY record_date ASC" +
            "</script>")
    List<BodyWeight> list(@Param("userId") Long userId,
                          @Param("start") LocalDate start,
                          @Param("end") LocalDate end);

    @Select("SELECT * FROM body_weight WHERE user_id = #{userId} AND record_date = #{recordDate}")
    BodyWeight findByDate(@Param("userId") Long userId, @Param("recordDate") LocalDate recordDate);

    @Select("SELECT * FROM body_weight WHERE id = #{id} AND user_id = #{userId}")
    BodyWeight findById(@Param("id") Long id, @Param("userId") Long userId);

    @Insert("INSERT INTO body_weight (user_id, record_date, weight, note) VALUES (#{userId}, #{recordDate}, #{weight}, #{note})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(BodyWeight bodyWeight);

    @Update("UPDATE body_weight SET record_date = #{recordDate}, weight = #{weight}, note = #{note} " +
            "WHERE id = #{id} AND user_id = #{userId}")
    int update(BodyWeight bodyWeight);

    @Delete("DELETE FROM body_weight WHERE id = #{id} AND user_id = #{userId}")
    int delete(@Param("id") Long id, @Param("userId") Long userId);
}
