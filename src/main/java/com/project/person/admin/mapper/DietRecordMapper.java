package com.project.person.admin.mapper;

import com.project.person.admin.model.DietRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface DietRecordMapper {

    @Select("SELECT * FROM diet_record WHERE user_id = #{userId} AND record_date = #{date} ORDER BY id ASC")
    List<DietRecord> listByDate(@Param("userId") Long userId, @Param("date") LocalDate date);

    /** 每日热量汇总：SELECT record_date, SUM(calories) ... GROUP BY record_date */
    @Select("SELECT record_date AS recordDate, SUM(calories) AS calories FROM diet_record " +
            "WHERE user_id = #{userId} AND record_date >= #{start} AND record_date <= #{end} " +
            "GROUP BY record_date ORDER BY record_date ASC")
    List<Map<String, Object>> dailyCalories(@Param("userId") Long userId,
                                            @Param("start") LocalDate start,
                                            @Param("end") LocalDate end);

    @Select("SELECT * FROM diet_record WHERE id = #{id} AND user_id = #{userId}")
    DietRecord findById(@Param("id") Long id, @Param("userId") Long userId);

    @Insert("INSERT INTO diet_record (user_id, record_date, meal_type, food, calories, remark) " +
            "VALUES (#{userId}, #{recordDate}, #{mealType}, #{food}, #{calories}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DietRecord dietRecord);

    @Update("UPDATE diet_record SET record_date = #{recordDate}, meal_type = #{mealType}, food = #{food}, " +
            "calories = #{calories}, remark = #{remark} WHERE id = #{id} AND user_id = #{userId}")
    int update(DietRecord dietRecord);

    @Delete("DELETE FROM diet_record WHERE id = #{id} AND user_id = #{userId}")
    int delete(@Param("id") Long id, @Param("userId") Long userId);
}
