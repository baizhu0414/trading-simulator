package com.example.trading.config;

import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * MyBatis 专属配置（与线程池配置解耦）
 */
@Configuration
@EnableTransactionManagement // 开启事务管理
@MapperScan("com.example.trading.mapper") // 扫描Mapper接口
public class MyBatisConfig {

    /**
     * 配置 SqlSessionFactory，开启缓存避免类加载飙升
     */
    @Bean
    public SqlSessionFactoryBean sqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        // 扫描 Mapper XML
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:mapper/**/*.xml"));
        // 设置实体类别名
        factoryBean.setTypeAliasesPackage("com.example.trading.domain.model");

        // 核心：开启 MyBatis 缓存，避免代理类重复创建
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
        config.setCacheEnabled(true); // 全局缓存开启
        config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.SESSION); // 会话级缓存
        config.setMapUnderscoreToCamelCase(true); // 下划线转驼峰
        config.setLogImpl(org.apache.ibatis.logging.slf4j.Slf4jImpl.class);
        factoryBean.setConfiguration(config);

        return factoryBean;
    }

    /**
     * 事务管理器
     */
    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}