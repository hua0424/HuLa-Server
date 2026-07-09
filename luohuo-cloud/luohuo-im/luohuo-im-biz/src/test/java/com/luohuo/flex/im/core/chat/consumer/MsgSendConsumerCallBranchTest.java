package com.luohuo.flex.im.core.chat.consumer;

import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.flex.common.OnlineService;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.im.core.chat.dao.RoomDao;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.user.dao.AiclawDao;
import com.luohuo.flex.im.core.user.dao.AiclawFriendExtDao;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.im.domain.MsgSendMessageDTO;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.msg.MessageExtra;
import com.luohuo.flex.im.domain.enums.MessageTypeEnum;
import com.luohuo.flex.im.domain.enums.RoomTypeEnum;
import com.luohuo.flex.im.domain.vo.response.msg.VideoCallMsgDTO;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.ChatMessageResp;
import org.junit.jupiter.api.DisplayName;
import com.luohuo.flex.im.core.chat.service.AiclawGroupConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #24: 验证 {@link MsgSendConsumer#onMessage} 在 AUDIO_CALL / VIDEO_CALL 分支
 * 推给其他成员（others）的消息里，发送者的 uid、userType、name 都被纠正回
 * originalFromUid 的值——而不是停留在通话创建者(creator)的 userType/name 上。
 *
 * <p>RED→GREEN 判定点：未修复前，consumer 只 setUid(originalFromUid)，
 * userType/name 仍是 creator 的（getMsgResp 在 fromUid==creator 时返回的值）；
 * 修复后复用 swap 前构建的 selfMsgResp(其 fromUser 按 originalFromUid 填好)的
 * userType/name 覆盖回去。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MsgSendConsumerCallBranchTest {

	@Mock private ChatService chatService;
	@Mock private MessageDao messageDao;
	@Mock private RoomCache roomCache;
	@Mock private RoomDao roomDao;
	@Mock private GroupMemberCache groupMemberCache;
	@Mock private RoomFriendDao roomFriendDao;
	@Mock private OnlineService onlineService;
	@Mock private PushService pushService;
	@Mock private CachePlusOps cachePlusOps;
	@Mock private AiclawDao aiclawDao;
	@Mock private AiclawFriendExtDao aiclawFriendExtDao;
	@Mock private UserSummaryCache userSummaryCache;
	@Mock private AiclawGroupConfigService aiclawGroupConfigService;

	@InjectMocks private MsgSendConsumer consumer;

	@BeforeEach
	void stubAiclawApprovalPassthrough() {
		// REQ-009#84: onMessage 群路径经 aiclawGroupConfigService 过滤收件人；本类不测该门控，
		// 透传原始列表以保持既有断言语义（#171：补 REQ-009 遗留的 @Mock）。类级 strictness=LENIENT。
		when(aiclawGroupConfigService.filterUnapprovedAiclawRecipients(anyList(), any()))
				.thenAnswer(inv -> inv.getArgument(0));
	}

	private static final Long ROOM_ID = 10L;
	private static final Long MSG_ID = 1L;
	private static final Long ORIGINAL_FROM_UID = 100L;
	private static final Long OTHER_MEMBER_UID = 300L;

	/** 构造一个带 fromUser + 空 message 的 ChatMessageResp（模拟 getMsgResp 的产物）。 */
	private ChatMessageResp resp(String uid, Integer userType, String name) {
		ChatMessageResp.UserInfo fromUser = new ChatMessageResp.UserInfo();
		fromUser.setUid(uid);
		fromUser.setUserType(userType);
		fromUser.setName(name);
		ChatMessageResp r = new ChatMessageResp();
		r.setFromUser(fromUser);
		// fillRoomType 对 message==null 是 no-op，但给个空 Message 更稳
		r.setMessage(new ChatMessageResp.Message());
		return r;
	}

	private Message buildCallMessage(Long creator) {
		VideoCallMsgDTO videoCall = VideoCallMsgDTO.builder().creator(creator).build();
		MessageExtra extra = MessageExtra.builder().videoCallMsgDTO(videoCall).build();
		Message message = new Message();
		message.setId(MSG_ID);
		message.setRoomId(ROOM_ID);
		message.setType(MessageTypeEnum.VIDEO_CALL.getType());
		message.setFromUid(ORIGINAL_FROM_UID);
		message.setExtra(extra);
		return message;
	}

	private Room buildGroupRoom() {
		Room room = new Room();
		room.setId(ROOM_ID);
		room.setType(RoomTypeEnum.GROUP.getType());
		return room;
	}

	/**
	 * getMsgResp 按调用时 message.getFromUid() 的当前值返回不同 resp：
	 * - fromUid==100(originalFromUid) → {uid=100, userType=3, name=Alice}
	 * - fromUid==200(creator)         → {uid=200, userType=4, name=Bot}
	 * 每次 thenAnswer 都 new 全新对象，避免引用串味。
	 */
	private void stubGetMsgRespByFromUid(Message message) {
		when(chatService.getMsgResp(eq(message), isNull())).thenAnswer(inv -> {
			Long fromUid = message.getFromUid();
			if (ORIGINAL_FROM_UID.equals(fromUid)) {
				return resp("100", 3, "Alice");
			}
			return resp("200", 4, "Bot");
		});
	}

	private MsgSendMessageDTO buildDto() {
		return MsgSendMessageDTO.builder()
				.msgId(MSG_ID)
				.uid(ORIGINAL_FROM_UID)
				.tenantId(null)
				.extra(null)
				.build();
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private ChatMessageResp captureOthersPushedData() {
		ArgumentCaptor<WsBaseResp> othersCaptor = ArgumentCaptor.forClass(WsBaseResp.class);
		// others 推送走 List<Long> 重载；self 推送走单 Long 重载，天然区分
		verify(pushService).sendPushMsg(othersCaptor.capture(), anyList(), any());
		return (ChatMessageResp) othersCaptor.getValue().getData();
	}

	@Test
	@DisplayName("#24: creator≠originalFromUid 时, others 推送的 fromUser.userType/name 被纠正回 originalFromUid 的(3/Alice)")
	void callOthersPushCorrectsUserTypeAndName() {
		Message message = buildCallMessage(200L); // creator=200 ≠ original=100
		Room room = buildGroupRoom();
		when(messageDao.getById(MSG_ID)).thenReturn(message);
		when(roomCache.get(ROOM_ID)).thenReturn(room);
		when(groupMemberCache.getMemberExceptUidList(ROOM_ID))
				.thenReturn(List.of(ORIGINAL_FROM_UID, OTHER_MEMBER_UID));
		// 可变 Set，因为分支里会 onlineUsersList.remove(uid)
		when(onlineService.getOnlineUsersList(any()))
				.thenReturn(new HashSet<>(Set.of(ORIGINAL_FROM_UID, OTHER_MEMBER_UID)));
		stubGetMsgRespByFromUid(message);

		consumer.onMessage(buildDto());

		ChatMessageResp.UserInfo fu = captureOthersPushedData().getFromUser();
		assertEquals("100", fu.getUid(), "uid 应被纠正回 originalFromUid");
		assertEquals(3, fu.getUserType(),
				"userType 应被纠正回 originalFromUid 的(3)，未修复时会是 creator 的(4)");
		assertEquals("Alice", fu.getName(),
				"name 应被纠正回 originalFromUid 的(Alice)，未修复时会是 creator 的(Bot)");
	}

	@Test
	@DisplayName("#24 guard: creator==originalFromUid 时 fix 不破坏正常情形(仍是 100/3/Alice)")
	void callOthersPushWhenCreatorEqualsOriginalKeepsCorrectUserTypeAndName() {
		Message message = buildCallMessage(ORIGINAL_FROM_UID); // creator==original==100
		Room room = buildGroupRoom();
		when(messageDao.getById(MSG_ID)).thenReturn(message);
		when(roomCache.get(ROOM_ID)).thenReturn(room);
		when(groupMemberCache.getMemberExceptUidList(ROOM_ID))
				.thenReturn(List.of(ORIGINAL_FROM_UID, OTHER_MEMBER_UID));
		when(onlineService.getOnlineUsersList(any()))
				.thenReturn(new HashSet<>(Set.of(ORIGINAL_FROM_UID, OTHER_MEMBER_UID)));
		stubGetMsgRespByFromUid(message);

		consumer.onMessage(buildDto());

		ChatMessageResp.UserInfo fu = captureOthersPushedData().getFromUser();
		assertEquals("100", fu.getUid());
		assertEquals(3, fu.getUserType(), "creator==original 时 fix 用相同值覆盖，无副作用");
		assertEquals("Alice", fu.getName(), "creator==original 时 fix 用相同值覆盖，无副作用");
	}
}
