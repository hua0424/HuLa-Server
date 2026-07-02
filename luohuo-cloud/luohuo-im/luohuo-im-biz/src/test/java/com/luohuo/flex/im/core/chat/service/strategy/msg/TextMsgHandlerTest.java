package com.luohuo.flex.im.core.chat.service.strategy.msg;

import com.luohuo.flex.im.common.utils.sensitiveword.SensitiveWordBs;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.im.core.user.service.RoleService;
import com.luohuo.flex.im.core.user.service.cache.UserCache;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.entity.msg.MessageExtra;
import com.luohuo.flex.im.domain.entity.msg.TextMsgReq;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F3-1 #42 关键正确性守卫：证明 buildMsgSave 放进 extra 的 clientMsgId
 * 会经由子类 handler 的 saveMsg（extra 合并 + updateById）存活到落库。
 * 这里用 TextMsgHandler 覆盖那条 {@code Optional.ofNullable(existing).orElse(new)} 分支。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TextMsgHandlerTest {

	@Mock
	private MessageDao messageDao;
	@Mock
	private UserCache userCache;
	@Mock
	private RoleService roleService;
	@Mock
	private SensitiveWordBs sensitiveWordBs;

	@InjectMocks
	private TextMsgHandler textMsgHandler;

	@Test
	@DisplayName("F3-1: saveMsg 保留 buildMsgSave 预置的 extra.clientMsgId（updateById 实体仍带它）")
	void saveMsgPreservesClientMsgIdInExtra() {
		when(sensitiveWordBs.filter(anyString())).thenAnswer(inv -> inv.getArgument(0));

		// 模拟 buildMsgSave 已把 clientMsgId 放进 extra 的 Message
		Message msg = Message.builder()
				.roomId(1L)
				.fromUid(100L)
				.type(1)
				.extra(MessageExtra.builder().clientMsgId("T-abc-123").build())
				.build();
		msg.setId(999L);

		TextMsgReq body = new TextMsgReq();
		body.setContent("hello world"); // 无 URL、无 @，走最简分支

		textMsgHandler.saveMsg(msg, body);

		ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
		verify(messageDao).updateById(captor.capture());
		Message persisted = captor.getValue();

		assertNotNull(persisted.getExtra(), "落库实体应带 extra");
		assertEquals("T-abc-123", persisted.getExtra().getClientMsgId(),
				"handler 的 saveMsg 不应丢弃 buildMsgSave 预置的 clientMsgId");
	}
}
