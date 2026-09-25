package com.luohuo.flex.im.core.chat.mapper;

import com.luohuo.flex.im.domain.entity.AiclawThinking;
import com.luohuo.flex.im.test.AbstractMapperIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real isolated MySQL exercise of tenant-scoped thinking SQL and concurrent terminal CAS. */
class ThinkingMapperSecurityTest extends AbstractMapperIT {
    @Autowired private AiclawThinkingMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // ponytail: 共享复用 MySQL 里 AiclawDissolutionCleanupMapperTest 会遗留 3001-3003 行（无 @AfterEach），
        // 全表 cleanup 扫描会看到它们；仅按其固定 id 清理，不动其逻辑。
        jdbc.update("DELETE FROM im_aiclaw_thinking WHERE id IN (3001,3002,3003)");
        jdbc.update("INSERT INTO im_user (id,tenant_id,user_type,state) "
                + "VALUES (92001,1,4,0),(92002,2,4,0),(92003,1,1,1),(92004,1,4,NULL)");
        jdbc.update("INSERT INTO im_aiclaw (id,uid,owner_uid,token_hash,token_prefix,tenant_id,auth_status) "
                + "VALUES (93001,92001,92001,'fixture-hash','t295a001',1,1),"
                + "(93002,92002,92002,'fixture-hash','t295a002',2,1),"
                + "(93004,92004,92004,'fixture-hash','t295a004',1,1)");
        jdbc.update("INSERT INTO im_room (id,type,tenant_id,create_by) VALUES (90001,1,1,92001),(90002,1,2,92002)");
        jdbc.update("INSERT INTO im_room_group (id,room_id,name,avatar,tenant_id,create_by) "
                + "VALUES (91001,90001,'g1','',1,92001),(91002,90002,'g2','',2,92002)");
        jdbc.update("INSERT INTO im_group_member (id,group_id,uid,role_id,tenant_id) "
                + "VALUES (95001,91001,92001,3,1),(95002,91002,92002,3,2),"
                + "(95003,91001,92003,3,1),(95004,91001,92004,3,1)");
        jdbc.update("INSERT INTO im_aiclaw_thinking (id,tenant_id,aiclaw_uid,room_id,content,status,create_time) "
                + "VALUES (94001,1,92001,90001,'',0,DATE_SUB(NOW(), INTERVAL 10 MINUTE)),"
                + "(94002,2,92002,90002,'',0,DATE_SUB(NOW(), INTERVAL 10 MINUTE)),"
                + "(94005,1,92001,90001,'',0,NOW())");
        jdbc.update("UPDATE im_aiclaw_thinking SET is_del=1 WHERE id=94005");
        assertEquals(1, mapper.insertThinking(94003L, 1L, 92001L, 90001L, null));
        assertEquals(1, mapper.insertThinking(94004L, 1L, 92004L, 90001L, null));
    }

    @AfterEach
    void deleteOnlyFixtureRows() {
        jdbc.update("DELETE FROM im_aiclaw_thinking WHERE id IN (94001,94002,94003,94004,94005)");
        jdbc.update("DELETE FROM im_group_member WHERE id IN (95001,95002,95003,95004)");
        jdbc.update("DELETE FROM im_room_group WHERE id IN (91001,91002)");
        jdbc.update("DELETE FROM im_room WHERE id IN (90001,90002)");
        jdbc.update("DELETE FROM im_aiclaw WHERE id IN (93001,93002,93004)");
        jdbc.update("DELETE FROM im_user WHERE id IN (92001,92002,92003,92004)");
    }

    @Test
    void scannerAndRecipientsRespectRealTenantMembershipAndRevocation() {
        assertEquals(1, mapper.isCurrentMember(92001L, 90001L, 1L));
        assertNull(mapper.isCurrentMember(92001L, 90002L, 2L));
        assertNull(mapper.selectOwned(94001L, 2L, 92001L, 90001L)); // tenant alone differs
        assertNull(mapper.selectOwned(94001L, 1L, 92002L, 90001L)); // actor alone differs
        assertNull(mapper.selectOwned(94001L, 1L, 92001L, 90002L)); // room alone differs
        assertNull(mapper.selectOwned(94005L, 1L, 92001L, 90001L)); // logically deleted
        assertEquals(List.of(92001L), mapper.selectCurrentMemberUids(90001L, 1L));
        List<AiclawThinking> expired = mapper.selectCleanupCandidates(LocalDateTime.now().minusMinutes(5), 0L);
        assertEquals(List.of(94001L, 94002L, 94004L), expired.stream().map(AiclawThinking::getId).toList());
        assertEquals(List.of(1L, 2L, 1L), expired.stream().map(AiclawThinking::getTenantId).toList());
        jdbc.update("UPDATE im_aiclaw SET auth_status=2 WHERE id=93001");
        assertEquals(List.of(), mapper.selectCurrentMemberUids(90001L, 1L));
        assertEquals(List.of(94003L, 94004L), mapper.selectCleanupCandidates(LocalDateTime.now().minusMinutes(5), 94002L)
                .stream().map(AiclawThinking::getId).toList());
        jdbc.update("UPDATE im_aiclaw SET auth_status=1 WHERE id=93001");
        jdbc.update("UPDATE im_group_member SET is_del=1 WHERE id=95001");
        assertNull(mapper.isCurrentMember(92001L, 90001L, 1L));
        assertEquals(List.of(), mapper.selectCurrentMemberUids(90001L, 1L));
    }

    @Test
    void concurrentEndsHaveOneWinnerAndCannotCrossTenantOrOverwrite() throws Exception {
        assertEquals(0, mapper.finalizeActive(94003L, 2L, 92002L, 90002L, "foreign", 1, 1, null));
        assertEquals(0, mapper.finalizeActive(94003L, 1L, 92002L, 90001L, "wrong actor", 1, 1, null));
        assertEquals(0, mapper.finalizeActive(94003L, 1L, 92001L, 90002L, "wrong room", 1, 1, null));
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var complete = pool.submit(() -> { gate.await(); return mapper.finalizeActive(94003L, 1L, 92001L, 90001L, "complete", 12, 1, null); });
            var error = pool.submit(() -> { gate.await(); return mapper.finalizeActive(94003L, 1L, 92001L, 90001L, "error", 13, 2, "failed"); });
            gate.countDown();
            int winners = complete.get(10, TimeUnit.SECONDS) + error.get(10, TimeUnit.SECONDS);
            assertEquals(1, winners);
            AiclawThinking stored = mapper.selectOwned(94003L, 1L, 92001L, 90001L);
            assertNotNull(stored);
            if (stored.getStatus() == 1) assertEquals("complete", stored.getContent());
            else { assertEquals(2, stored.getStatus()); assertEquals("error", stored.getContent()); }
            assertEquals(0, mapper.finalizeActive(94003L, 1L, 92001L, 90001L, "overwrite", 14, 2, "changed"));
        } finally {
            gate.countDown();
            pool.shutdownNow();
        }
    }
}
