package com.example.trading.mapper;

import com.example.trading.domain.model.Trade;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TradeMapper {
    /**
     * 插入成交记录
     */
    int insert(Trade trade);

    /**
     * 批量插入成交记录
     */
    int batchInsert(@Param("trades") List<Trade> trades);

    /**
     * 根据订单唯一ID查询成交记录
     */
    List<Trade> selectByClOrderId(@Param("clOrderId") String clOrderId);

    /**
     * 根据成交编号查询
     */
    Trade selectByExecId(@Param("execId") String execId);

    /**
     * 根据成交编号判断是否存在，唯一
     */
    int existsByExecId(@Param("execId") String execId);
}