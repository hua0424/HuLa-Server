package com.luohuo.flex.im.core.chat.mapper;

import com.luohuo.flex.im.test.AbstractMapperIT;
import com.luohuo.flex.model.entity.ws.ChatMemberResp;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** #332: active memberships must not revive deleted or cross-tenant users. */
class GroupMemberMapperDeletedUserTest extends AbstractMapperIT {
	@Resource private GroupMemberMapper mapper;
	@Resource private JdbcTemplate jdbc;

	@Test
	void listMemberOnlyIncludesActiveUsersOfSameTenant() {
		long groupId = 33200L;
		long uid = 33201L;
		long deletedUid = 33202L;
		long foreignUid = 33203L;
		try {
			jdbc.update("INSERT INTO im_user(id, tenant_id, user_type, is_del) VALUES (?, 1, 1, 0), (?, 1, 4, 1), (?, 2, 1, 0)",
					uid, deletedUid, foreignUid);
			jdbc.update("INSERT INTO im_group_member(id, group_id, uid, role_id, tenant_id, is_del) VALUES "
					+ "(33211, ?, ?, 1, 1, 0), (33212, ?, ?, 3, 1, 0), (33213, ?, ?, 3, 1, 0), (33214, ?, ?, 1, 1, 1)",
					groupId, uid, groupId, deletedUid, groupId, foreignUid, groupId, uid);

			List<ChatMemberResp> members = mapper.getMemberListByGroupId(groupId);
			assertEquals(List.of(String.valueOf(uid)), members.stream().map(ChatMemberResp::getUid).toList());
		} finally {
			jdbc.update("DELETE FROM im_group_member WHERE group_id = ?", groupId);
			jdbc.update("DELETE FROM im_user WHERE id IN (?, ?, ?)", uid, deletedUid, foreignUid);
		}
	}
}
