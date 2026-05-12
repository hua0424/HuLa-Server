package com.luohuo.flex.im.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * ISS-003~006 Mapper 集成测试根 Bean。
 *
 * <p>项目业务用 Druid 池,父 POM(luohuo-databases)将 HikariCP 从 spring-boot-starter-jdbc 显式排除,
 * 因此 Spring Boot DataSourceAutoConfiguration 在测试上下文下无法兜底产生 DataSource。
 * 测试这里显式装配 DriverManagerDataSource(pool-less,每次 getConnection 新建),
 * 既避开 Druid 监控/Filter 链对测试上下文的污染,也满足并发用例的连接独立性。
 *
 * <p>SqlSessionFactory 显式 @Bean 注入,避开 MybatisPlusAutoConfiguration 在最小上下文下的 ConditionalOn 链路不齐问题。
 * 不引入 Spring Cloud / Nacos / Web 等业务上下文。
 */
@SpringBootConfiguration
@MapperScan("com.luohuo.flex.im.core.chat.mapper")
@ImportAutoConfiguration({
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        TransactionAutoConfiguration.class
})
public class MapperTestApp {

    @Bean
    public DataSource dataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.driver-class-name}") String driverClassName) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(driverClassName);
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        return ds;
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:mapper/**/*.xml"));
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(cfg);
        return factoryBean.getObject();
    }
}
