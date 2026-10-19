package com.project.person.admin.mapper;

import com.project.person.admin.model.Expense;
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
public interface ExpenseMapper {

    @Select("<script>" +
            "SELECT e.*, a.name AS asset_name, i.name AS investment_name FROM expense e " +
            "LEFT JOIN asset a ON a.id = e.asset_id " +
            "LEFT JOIN investment i ON i.id = e.investment_id " +
            "WHERE e.user_id = #{userId} " +
            "AND e.record_date &gt;= #{start} AND e.record_date &lt; #{end} " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND e.remark LIKE CONCAT('%', #{keyword}, '%') " +
            "</if>" +
            "ORDER BY e.record_date DESC, e.id DESC LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    List<Expense> page(@Param("userId") Long userId,
                       @Param("start") LocalDate start,
                       @Param("end") LocalDate end,
                       @Param("keyword") String keyword,
                       @Param("limit") int limit,
                       @Param("offset") int offset);

    @Select("<script>" +
            "SELECT COUNT(*) FROM expense WHERE user_id = #{userId} " +
            "AND record_date &gt;= #{start} AND record_date &lt; #{end} " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND remark LIKE CONCAT('%', #{keyword}, '%') " +
            "</if>" +
            "</script>")
    long count(@Param("userId") Long userId,
               @Param("start") LocalDate start,
               @Param("end") LocalDate end,
               @Param("keyword") String keyword);

    @Select("SELECT * FROM expense WHERE id = #{id} AND user_id = #{userId}")
    Expense findById(@Param("id") Long id, @Param("userId") Long userId);

    @Insert("INSERT INTO expense (user_id, amount, category, record_date, asset_id, investment_id, remark) " +
            "VALUES (#{userId}, #{amount}, #{category}, #{recordDate}, #{assetId}, #{investmentId}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Expense expense);

    @Update("UPDATE expense SET amount = #{amount}, category = #{category}, " +
            "record_date = #{recordDate}, asset_id = #{assetId}, investment_id = #{investmentId}, remark = #{remark} " +
            "WHERE id = #{id} AND user_id = #{userId}")
    int update(Expense expense);

    @Delete("DELETE FROM expense WHERE id = #{id} AND user_id = #{userId}")
    int delete(@Param("id") Long id, @Param("userId") Long userId);

    /** 分类为「投资」的记录只是资产↔投资的划转，不计入支出统计 */
    String EXCLUDE_INVEST = " AND category <> '投资' ";

    @Select("SELECT IFNULL(SUM(amount), 0) FROM expense WHERE user_id = #{userId} AND record_date >= #{firstDay}" + EXCLUDE_INVEST)
    BigDecimal sumFrom(@Param("userId") Long userId, @Param("firstDay") LocalDate firstDay);

    /** 按月分组汇总（MySQL 语法；切回 H2 需改回 FORMATDATETIME(record_date, 'yyyy-MM')） */
    @Select("SELECT DATE_FORMAT(record_date, '%Y-%m') AS ym, SUM(amount) AS total " +
            "FROM expense WHERE user_id = #{userId} AND record_date >= #{start} AND record_date < #{end}" + EXCLUDE_INVEST +
            "GROUP BY DATE_FORMAT(record_date, '%Y-%m')")
    List<Map<String, Object>> sumByMonth(@Param("userId") Long userId,
                                         @Param("start") LocalDate start,
                                         @Param("end") LocalDate end);

    /** 支出分类汇总（饼图） */
    @Select("SELECT category AS category, SUM(amount) AS total FROM expense " +
            "WHERE user_id = #{userId} AND record_date >= #{start} AND record_date < #{end}" + EXCLUDE_INVEST +
            "GROUP BY category")
    List<Map<String, Object>> sumByCategory(@Param("userId") Long userId,
                                            @Param("start") LocalDate start,
                                            @Param("end") LocalDate end);
}
