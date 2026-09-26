package com.luohuo.flex.im.core.user.mapper;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import com.luohuo.flex.im.test.AbstractMapperIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

class AiclawRestoreMapperTest extends AbstractMapperIT {

    @Autowired private AiclawMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM im_aiclaw WHERE id IN (29700, 29701)");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void restoreClearsPersistedDeactivationForBothPriorStates(int priorStatus) {
        long id = 29700L + priorStatus;
        jdbc.update("INSERT INTO im_aiclaw (id, uid, owner_uid, token_hash, token_prefix, tenant_id, auth_status, deactivated_at) "
                + "VALUES (?, ?, 200, 'hash', ?, 1, 2, NOW())", id, id, "r297000" + priorStatus);

        mapper.update(null, new LambdaUpdateWrapper<Aiclaw>()
                .eq(Aiclaw::getId, id)
                .set(Aiclaw::getAuthStatus, priorStatus)
                .set(Aiclaw::getDeactivatedAt, null));

        assertEquals(priorStatus, jdbc.queryForObject(
                "SELECT auth_status FROM im_aiclaw WHERE id = ?", Integer.class, id));
        assertNull(jdbc.queryForObject(
                "SELECT deactivated_at FROM im_aiclaw WHERE id = ?", java.sql.Timestamp.class, id),
                "真实 MySQL 更新后停用时间必须为 NULL，否则回源鉴权/写路径仍拒绝恢复身份");
    }
}
