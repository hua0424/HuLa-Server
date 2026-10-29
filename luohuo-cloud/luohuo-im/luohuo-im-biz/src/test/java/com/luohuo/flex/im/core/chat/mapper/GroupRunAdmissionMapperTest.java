package com.luohuo.flex.im.core.chat.mapper;

import com.luohuo.flex.im.domain.entity.RoomFriend;
import com.luohuo.flex.im.test.AbstractMapperIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 MySQL 上验证准入事实读：#485 起单聊的成员事实取自 im_room_friend 行，不能沿用群成员表。
 *
 * <p>本类只覆盖 mapper 层（服务层判定由 {@code GroupRunAdmissionServiceTest} 用 mock 覆盖），用来证明 SQL
 * 在真实方言下确实读到了单聊行、且把「不同租户 / 非本房成员 / 已删好友」排除掉。</p>
 */
class GroupRunAdmissionMapperTest extends AbstractMapperIT {
    private static final long ACTOR = 92011L;
    private static final long PEER = 92012L;
    private static final long TENANT = 1L;
    private static final long ROOM = 90101L;
    private static final long FRIEND = 91101L;
    private static final long GROUP = 91011L;
    private static final long MEMBER = 95011L;

    @Autowired private GroupRunAdmissionMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO im_user (id,tenant_id,user_type,state) VALUES (?,?,4,0)", ACTOR, TENANT);
        jdbc.update("INSERT INTO im_aiclaw (id,uid,owner_uid,token_hash,token_prefix,tenant_id,auth_status) "
                + "VALUES (?,?,?,'fixture-hash','t485f001',?,1)", ACTOR + 1000, ACTOR, ACTOR, TENANT);
        jdbc.update("INSERT INTO im_room (id,type,tenant_id,create_by) VALUES (?,2,?,?)", ROOM, TENANT, ACTOR);
        jdbc.update("INSERT INTO im_room_friend (id,room_id,uid1,uid2,room_key,de_friend1,de_friend2,tenant_id) "
                + "VALUES (?,?,?,?,?,0,0,?)", FRIEND, ROOM, ACTOR, PEER, ACTOR + "_" + PEER, TENANT);
    }

    @AfterEach
    void deleteOnlyFixtureRows() {
        jdbc.update("DELETE FROM im_room_friend WHERE id = ?", FRIEND);
        jdbc.update("DELETE FROM im_room WHERE id = ?", ROOM);
        jdbc.update("DELETE FROM im_aiclaw WHERE uid = ?", ACTOR);
        jdbc.update("DELETE FROM im_user WHERE id = ?", ACTOR);
    }

    @Test
    void friendRoomMembershipIsReadFromRoomFriendRow() {
        RoomFriend locked = mapper.lockFriendMembership(ACTOR, ROOM, TENANT);
        assertNotNull(locked);
        assertEquals(FRIEND, locked.getId());

        // 对端同样有权：单聊两方都是成员。
        assertNotNull(mapper.selectFriendMembership(PEER, ROOM, TENANT));
        // 非本房第三方、错租户、错房间都读不到。
        assertNull(mapper.selectFriendMembership(ACTOR + 99, ROOM, TENANT));
        assertNull(mapper.selectFriendMembership(ACTOR, ROOM, TENANT + 1));
        assertNull(mapper.selectFriendMembership(ACTOR, ROOM + 1, TENANT));
    }

    @Test
    void blockedOrDeletedFriendRowStopsBeingAMembershipFact() {
        // 拉黑不删行：两个方向都必须不再表现为可执行成员事实（语义与 ChatServiceImpl.checkDeFriend 一致）。
        jdbc.update("UPDATE im_room_friend SET de_friend2 = 1 WHERE id = ?", FRIEND);
        RoomFriend blocked = mapper.lockFriendMembership(ACTOR, ROOM, TENANT);
        assertNotNull(blocked);
        assertTrue(blocked.getDeFriend2());

        jdbc.update("UPDATE im_room_friend SET de_friend2 = 0, is_del = 1 WHERE id = ?", FRIEND);
        assertNull(mapper.selectFriendMembership(ACTOR, ROOM, TENANT));
    }

    @Test
    void groupRoomStillUsesGroupMemberFact() {
        // 群路径未被单聊改动影响：成员事实仍来自 im_room_group ⋈ im_group_member。
        jdbc.update("UPDATE im_room SET type = 1 WHERE id = ?", ROOM);
        jdbc.update("INSERT INTO im_room_group (id,room_id,name,avatar,tenant_id,create_by) "
                + "VALUES (?,?,'g','',?,?)", GROUP, ROOM, TENANT, ACTOR);
        jdbc.update("INSERT INTO im_group_member (id,group_id,uid,role_id,tenant_id) VALUES (?,?,?,3,?)",
                MEMBER, GROUP, ACTOR, TENANT);
        try {
            assertEquals(MEMBER, mapper.selectMembership(ACTOR, ROOM, TENANT));
            assertNull(mapper.selectMembership(PEER, ROOM, TENANT));
        } finally {
            jdbc.update("DELETE FROM im_group_member WHERE id = ?", MEMBER);
            jdbc.update("DELETE FROM im_room_group WHERE id = ?", GROUP);
        }
    }
}
