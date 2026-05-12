package com.luohuo.flex.im.test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * ISS-003~006 Mapper 集成测试基类。
 *
 * <p>Testcontainers 全局单例 MySQL,容器复用减少跨测试启动开销;首次启动时通过 withInitScript 注入 IM 表 DDL。
 *
 * <p>每个具体测试类通过 {@code @Sql} 或在 {@code @BeforeEach} 中手工 TRUNCATE 隔离数据。
 *
 * <p>需要 Docker 守护进程可用(开发场景 /var/run/docker.sock 挂载)。
 */
@SpringBootTest(classes = MapperTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractMapperIT {

    @SuppressWarnings("resource")
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("im_test")
            .withUsername("im")
            .withPassword("im")
            .withInitScript("schema-im.sql")
            .withReuse(true);

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }
}
