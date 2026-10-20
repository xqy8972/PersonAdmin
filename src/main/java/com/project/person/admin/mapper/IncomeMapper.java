package com.project.person.admin.mapper;

import com.project.person.admin.model.Income;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface IncomeMapper {

    @Select("<script>" +
            "SELECT i.*, a.name AS asset_name FROM income i " +
            "LEFT JOIN asset a ON a.id = i.asset_id " +
            "WHERE i.user_id = #{userId} " +
            "AND i.record_date &gt;= #{start} AND i.record_date &lt; #{end} " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND i.remark LIKE CONCAT('%', #{keyword}, '%') " +
            "</if>" +
            "ORDER BY i.record_date DESC, i.id DESC LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    List<Income> page(@Param("userId") Long userId,
                      @Param("start") LocalDate start,
                      @Param("end") LocalDate end,
                      @Param("keyword") String keyword,
                      @Param("limit") int limit,
                      @Param("offset") int offset);

    @Select("<script>" +
            "SELECT COUNT(*) FROM income WHERE user_id = #{userId} " +
            "AND record_date &gt;= #{start} AND record_date &lt; #{end} " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND remark LIKE CONCAT('%', #{keyword}, '%') " +
            "</if>" +
            "</script>")
    long count(@Param("userId") Long userId,
               @Param("start") LocalDate start,
               @Param("end") LocalDate end,
               @Param("keyword") String keyword);

    @Select("SELECT * FROM income WHERE id = #{id} AND user_id = #{userId}")
    Income findById(@Param("id") Long id, @Param("userId") Long userId);

    @Insert("INSERT INTO income (user_id, amount, category, record_date, asset_id, remark) " +
            "VALUES (#{userId}, #{amount}, #{category}, #{recordDate}, #{assetId}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Income income);

    @Update("UPDATE income SET amount = #{amount}, category = #{category}, " +
            "record_date = #{recordDate}, asset_id = #{assetId}, remark = #{remark} " +
            "WHERE id = #{id} AND user_id = #{userId}")
    int update(Income income);

    @Delete("DELETE FROM income WHERE id = #{id} AND user_id = #{userId}")
    int delete(@Param("id") Long id, @Param("userId") Long userId);

    @Select("SELECT IFNULL(SUM(amount), 0) FROM income WHERE user_id = #{userId} AND record_date >= #{firstDay}")
    BigDecimal sumFrom(@Param("userId") Long userId, @Param("firstDay") LocalDate firstDay);

    /** 按月分组汇总（MySQL 语法；切回 H2 需改回 FORMATDATETIME(record_date, 'yyyy-MM')） */
    @Select("SELECT DATE_FORMAT(record_date, '%Y-%m') AS ym, SUM(amount) AS total " +
            "FROM income WHERE user_id = #{userId} AND record_date >= #{start} AND record_date < #{end} " +
            "GROUP BY DATE_FORMAT(record_date, '%Y-%m')")
    List<Map<String, Object>> sumByMonth(@Param("userId") Long userId,
                                         @Param("start") LocalDate start,
                                         @Param("end") LocalDate end);

    /** 收入分类汇总（饼图） */
    @Select("SELECT category AS category, SUM(amount) AS total FROM income " +
            "WHERE user_id = #{userId} AND record_date >= #{start} AND record_date < #{end} " +
            "GROUP BY category")
    List<Map<String, Object>> sumByCategory(@Param("userId") Long userId,
                                            @Param("start") LocalDate start,
                                            @Param("end") LocalDate end);
}
