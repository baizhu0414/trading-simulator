package com.example.trading.common.typehandler;

import com.example.trading.common.enums.SideEnum;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 买卖方向枚举类型处理器，适配数据库B/S ↔ Java SideEnum
 */
@MappedTypes(SideEnum.class) // 映射Java枚举类型
@MappedJdbcTypes(JdbcType.VARCHAR) // 映射数据库VARCHAR类型
public class SideEnumTypeHandler extends BaseTypeHandler<SideEnum> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, SideEnum parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, parameter.getCode()); // 存入B/S
    }

    @Override
    public SideEnum getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String code = rs.getString(columnName);
        return SideEnum.getByCode(code);
    }

    @Override
    public SideEnum getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String code = rs.getString(columnIndex);
        return SideEnum.getByCode(code);
    }

    @Override
    public SideEnum getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String code = cs.getString(columnIndex);
        return SideEnum.getByCode(code);
    }
}