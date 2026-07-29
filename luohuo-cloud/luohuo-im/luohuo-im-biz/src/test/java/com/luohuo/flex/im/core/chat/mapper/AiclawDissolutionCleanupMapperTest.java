package com.luohuo.flex.im.core.chat.mapper;

import com.luohuo.flex.im.domain.entity.AiclawGroupConfig;
import com.luohuo.flex.im.domain.entity.AiclawThinking;
import com.luohuo.flex.im.domain.entity.AiclawThinkingMsgRel;
import com.luohuo.flex.im.test.AbstractMapperIT;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #182: 解散群聊时 aiclaw 扩展表清理的 Mapper 集成测试。
 *
 * <p>验证按 room_id 清理 im_aiclaw_group_config / im_aiclaw_thinking / im_aiclaw_thinking_msg_rel
 * 只影响被解散房间，不影响其他房间。</p>
 */
class AiclawDissolutionCleanupMapperTest extends AbstractMapperIT {

	@Resource
	private AiclawGroupConfigMapper aiclawGroupConfigMapper;
	@Resource
	private AiclawThinkingMapper aiclawThinkingMapper;
	@Resource
	private AiclawThinkingMsgRelMapper aiclawThinkingMsgRelMapper;
	@Resource
	private JdbcTemplate jdbc;

	private static final Long ROOM_DISSOLVED = 1001L;
	private static final Long ROOM_OTHER = 1002L;
	private static final Long AICLAW_A = 2001L;
	private static final Long AICLAW_B = 2002L;
	private static final Long THINKING_A = 3001L;
	private static final Long THINKING_B = 3002L;
	private static final Long THINKING_OTHER = 3003L;
	private static final Long MSG_A = 4001L;
	private static final Long MSG_B = 4002L;
	private static final Long MSG_OTHER = 4003L;

	@BeforeEach
	void clean() {
		jdbc.update("DELETE FROM im_aiclaw_group_config WHERE room_id IN (?, ?)", ROOM_DISSOLVED, ROOM_OTHER);
		jdbc.update("DELETE FROM im_aiclaw_thinking_msg_rel WHERE thinking_id IN (?, ?, ?)", THINKING_A, THINKING_B, THINKING_OTHER);
		jdbc.update("DELETE FROM im_aiclaw_thinking WHERE room_id IN (?, ?)", ROOM_DISSOLVED, ROOM_OTHER);
	}

	@Test
	@DisplayName("解散房间清理：只删除该房间的 config/thinking/relations，保留其他房间")
	void cleanupByRoomId_removesOnlyTargetRoom() {
		// given: 被解散房间有两条配置、两条 thinking、两条 relation
		aiclawGroupConfigMapper.insert(configOf(1L, AICLAW_A, ROOM_DISSOLVED));
		aiclawGroupConfigMapper.insert(configOf(2L, AICLAW_B, ROOM_DISSOLVED));
		aiclawThinkingMapper.insert(thinkingOf(THINKING_A, AICLAW_A, ROOM_DISSOLVED));
		aiclawThinkingMapper.insert(thinkingOf(THINKING_B, AICLAW_B, ROOM_DISSOLVED));
		aiclawThinkingMsgRelMapper.insertIgnore(relOf(THINKING_A, MSG_A));
		aiclawThinkingMsgRelMapper.insertIgnore(relOf(THINKING_B, MSG_B));

		// given: 其他房间各一条，不应被误删
		aiclawGroupConfigMapper.insert(configOf(3L, AICLAW_A, ROOM_OTHER));
		aiclawThinkingMapper.insert(thinkingOf(THINKING_OTHER, AICLAW_A, ROOM_OTHER));
		aiclawThinkingMsgRelMapper.insertIgnore(relOf(THINKING_OTHER, MSG_OTHER));

		// when: 执行解散清理
		aiclawGroupConfigMapper.deleteByRoomId(ROOM_DISSOLVED);
		aiclawThinkingMsgRelMapper.deleteByRoomId(ROOM_DISSOLVED);
		int updatedThinking = aiclawThinkingMapper.logicDeleteByRoomId(ROOM_DISSOLVED);

		// then: config 只剩其他房间
		List<Long> remainingConfigRoomIds = jdbc.queryForList(
				"SELECT room_id FROM im_aiclaw_group_config ORDER BY room_id", Long.class);
		assertThat(remainingConfigRoomIds).containsExactly(ROOM_OTHER);

		// then: thinking 被解散房间逻辑删除，其他房间保留
		assertThat(updatedThinking).isEqualTo(2);
		Integer dissolvedIsDel = jdbc.queryForObject(
				"SELECT is_del FROM im_aiclaw_thinking WHERE id = ?", Integer.class, THINKING_A);
		assertThat(dissolvedIsDel).isEqualTo(1);
		Integer otherIsDel = jdbc.queryForObject(
				"SELECT is_del FROM im_aiclaw_thinking WHERE id = ?", Integer.class, THINKING_OTHER);
		assertThat(otherIsDel).isEqualTo(0);

		// then: relations 只剩其他房间
		List<Long> remainingRelThinkingIds = jdbc.queryForList(
				"SELECT thinking_id FROM im_aiclaw_thinking_msg_rel ORDER BY thinking_id", Long.class);
		assertThat(remainingRelThinkingIds).containsExactly(THINKING_OTHER);
	}

	private AiclawGroupConfig configOf(Long id, Long aiclawUid, Long roomId) {
		AiclawGroupConfig c = new AiclawGroupConfig();
		c.setId(id);
		c.setAiclawUid(aiclawUid);
		c.setRoomId(roomId);
		c.setRateLimitPerMinute(10);
		c.setMentionRequired(1);
		c.setDailyLimit(1000);
		c.setRespondToAi(1);
		c.setApproved(1);
		c.setCreateTime(LocalDateTime.now());
		c.setUpdateTime(LocalDateTime.now());
		return c;
	}

	private AiclawThinking thinkingOf(Long id, Long aiclawUid, Long roomId) {
		AiclawThinking t = new AiclawThinking();
		t.setId(id);
		t.setAiclawUid(aiclawUid);
		t.setRoomId(roomId);
		t.setContent("test");
		t.setCreateTime(LocalDateTime.now());
		return t;
	}

	private AiclawThinkingMsgRel relOf(Long thinkingId, Long msgId) {
		return AiclawThinkingMsgRel.builder()
				.thinkingId(thinkingId)
				.msgId(msgId)
				.createTime(LocalDateTime.now())
				.build();
	}
}
