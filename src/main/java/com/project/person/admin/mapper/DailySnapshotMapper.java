package com.project.person.admin.mapper;

import com.project.person.admin.model.DailySnapshot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface DailySnapshotMapper {

    /**
     * 按 (user_id, snapshot_date, kind, ref_id) 唯一键 upsert 当日快照。
     * MySQL 语法；切回 H2 需改回 MERGE INTO ... KEY(...)
     */
    @Insert("INSERT INTO daily_snapshot (user_id, snapshot_date, kind, ref_id, name, type, total_value, invest_amount, price) " +
            "VALUES (#{userId}, #{snapshotDate}, #{kind}, #{refId}, #{name}, #{type}, #{totalValue}, #{investAmount}, #{price}) AS new " +
            "ON DUPLICATE KEY UPDATE name = new.name, type = new.type, " +
            "total_value = new.total_value, invest_amount = new.invest_amount, price = new.price")
    int upsert(DailySnapshot snapshot);

    /**
     * 按天聚合投资快照的总市值与累计投入（盈亏曲线用）。
     * 老数据 invest_amount 为空时按 total_value 计（当日盈亏记 0），新快照起正常累计。
     */
    @Select("SELECT snapshot_date, SUM(total_value) AS total_value, " +
            "SUM(COALESCE(invest_amount, total_value)) AS invest_amount " +
            "FROM daily_snapshot WHERE user_id = #{userId} AND kind = 'INVEST' " +
            "AND snapshot_date >= #{start} AND snapshot_date <= #{end} " +
            "GROUP BY snapshot_date ORDER BY snapshot_date ASC")
    List<DailySnapshot> sumInvestByDay(@Param("userId") Long userId,
                                       @Param("start") LocalDate start,
                                       @Param("end") LocalDate end);

    @Select("SELECT * FROM daily_snapshot WHERE user_id = #{userId} AND kind = #{kind} " +
            "AND snapshot_date >= #{start} AND snapshot_date <= #{end} " +
            "ORDER BY snapshot_date ASC")
    List<DailySnapshot> listByRange(@Param("userId") Long userId,
                                    @Param("kind") String kind,
                                    @Param("start") LocalDate start,
                                    @Param("end") LocalDate end);

    /**
     * 某标的在快照表中、早于指定日期的历史记录数。
     * 新增投资判断走势图是否已有历史时排除今日（今日快照在回填前就已写入）
     */
    @Select("SELECT COUNT(*) FROM daily_snapshot WHERE user_id = #{userId} AND kind = #{kind} " +
            "AND ref_id = #{refId} AND snapshot_date < #{before}")
    int countHistoryBefore(@Param("userId") Long userId,
                           @Param("kind") String kind,
                           @Param("refId") Long refId,
                           @Param("before") LocalDate before);

    /** 删除某标的的全部历史快照（资产/投资删除或卖出时清痕，折线图上整条移除） */
    @Delete("DELETE FROM daily_snapshot WHERE user_id = #{userId} AND kind = #{kind} AND ref_id = #{refId}")
    int deleteByRef(@Param("userId") Long userId, @Param("kind") String kind, @Param("refId") Long refId);

    /** 删除某标的早于指定日期的快照（编辑入账日期重定起点用：新起点之前的历史不再展示） */
    @Delete("DELETE FROM daily_snapshot WHERE user_id = #{userId} AND kind = #{kind} AND ref_id = #{refId} " +
            "AND snapshot_date < #{before}")
    int deleteBefore(@Param("userId") Long userId, @Param("kind") String kind,
                     @Param("refId") Long refId, @Param("before") LocalDate before);

    /**
     * 多个标的各自最近 N 条带行情价的快照（按 refId 分组取日期倒序前 N）。
     * 「最新盈亏」= 最近两个交易日收盘价差 × 持有数量；收盘表无记录时按 K 线快照
     * 跳过尾部价格相同的平推点（周末/节假日补齐）后对价。refIds 为逗号分隔的 id 字符串。
     */
    @Select("SELECT * FROM (SELECT t.*, ROW_NUMBER() OVER (PARTITION BY ref_id ORDER BY snapshot_date DESC) rn " +
            "FROM daily_snapshot t WHERE user_id = #{userId} AND kind = #{kind} " +
            "AND price IS NOT NULL AND ref_id IN (${refIds})) x WHERE rn <= #{limit} " +
            "ORDER BY ref_id, snapshot_date DESC")
    List<DailySnapshot> listRecentPrices(@Param("userId") Long userId,
                                         @Param("kind") String kind,
                                         @Param("refIds") String refIds,
                                         @Param("limit") int limit);
}
