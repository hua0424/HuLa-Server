package com.luohuo.flex.im.core.chat.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.luohuo.basic.utils.SpringUtils;
import com.luohuo.basic.utils.TimeUtils;
import com.luohuo.flex.im.core.chat.dao.*;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.MsgCache;
import com.luohuo.flex.im.core.user.dao.UserFriendDao;
import com.luohuo.flex.im.domain.entity.*;
import com.luohuo.flex.im.domain.enums.*;
import com.luohuo.flex.im.domain.vo.request.*;
import jakarta.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.luohuo.basic.exception.BizException;
import com.luohuo.basic.validator.utils.AssertUtil;
import com.luohuo.flex.model.redis.annotation.RedissonLock;
import com.luohuo.flex.im.domain.vo.res.CursorPageBaseResp;
import com.luohuo.flex.im.common.event.MessageSendEvent;
import com.luohuo.flex.im.domain.dto.ChatMsgSendDto;
import com.luohuo.flex.im.domain.dto.MsgReadInfoDTO;
import com.luohuo.flex.im.domain.vo.response.ChatMessageReadResp;
import com.luohuo.flex.model.entity.ws.ChatMessageResp;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.core.chat.service.ContactService;
import com.luohuo.flex.im.core.chat.service.adapter.MessageAdapter;
import com.luohuo.flex.im.core.chat.service.adapter.RoomAdapter;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.chat.service.strategy.mark.AbstractMsgMarkStrategy;
import com.luohuo.flex.im.core.chat.service.strategy.mark.MsgMarkFactory;
import com.luohuo.flex.im.core.chat.service.strategy.msg.AbstractMsgHandler;
import com.luohuo.flex.im.core.chat.service.strategy.msg.MsgHandlerFactory;
import com.luohuo.flex.im.core.chat.service.strategy.msg.RecallMsgHandler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.luohuo.flex.im.common.config.ThreadPoolConfig.LUOHUO_EXECUTOR;

@Service
@Slf4j
@AllArgsConstructor
public class ChatServiceImpl implements ChatService {
    private final UserFriendDao userFriendDao;
	private final GroupMemberCache groupMemberCache;
	private MsgCache msgCache;
    private MessageDao messageDao;
    private MessageMarkDao messageMarkDao;
    private RoomFriendDao roomFriendDao;
    private RecallMsgHandler recallMsgHandler;
    private ContactService contactService;
    private ContactDao contactDao;
    private RoomCache roomCache;
    private GroupMemberDao groupMemberDao;
    /**
     * 发送消息
     */
    @Override
    @Transactional
    public Long sendMsg(ChatMessageReq request, Long uid) {
        check(true, request.isSkip(), request.isTemp(), request.getRoomId(), uid);
        AbstractMsgHandler<?> msgHandler = MsgHandlerFactory.getStrategyNoNull(request.getMsgType());
        Long msgId = msgHandler.checkAndSaveMsg(request, uid);

        // ISS-003: 同事务推进房间内所有成员的 contact.last_msg_id,避免写入路径与 /chat/msg/page 游标失同步
        syncContactLastMsgId(request.getRoomId(), msgId);

		// 临时会话单独处理一下消息计数器
		if (request.isTemp()) {
			Room room = roomCache.get(request.getRoomId());
			if(room != null && room.isRoomFriend() && !request.isPushMessage()){
				UserFriend userFriend = userFriendDao.getByRoomId(request.getRoomId(), uid);
				if (userFriend != null && userFriend.getIsTemp()) {
					userFriend.setTempMsgCount(userFriend.getTempMsgCount() + 1);
					userFriendDao.updateById(userFriend);
				}
			}
		}

        // TODO(ISS-00X): Room.last_msg_id 在 skipPush=true(stream_end)路径下不更新,最近会话列表会显示错误,另起 issue
        // 发布消息发送事件（skipPush=true 时仅存库不推送，用于流式消息 stream_end 落库）
        if (!request.isSkipPush()) {
            SpringUtils.publishEvent(new MessageSendEvent(this, new ChatMsgSendDto(msgId, uid)));
        }
        return msgId;
    }

    /**
     * ISS-003 / ISS-006: 同事务推进房间内所有成员的 {@code contact.last_msg_id} 与 {@code contact.active_time}。
     *
     * <p>调用方:{@link #sendMsg},运行在 {@code @Transactional} 内,与 {@code im_message} 插入构成原子写。
     * 这样 WS 推送后客户端调 {@code /chat/msg/page} 时游标已经推进,不会再因 {@code Message.id <= contact.last_msg_id}
     * 漏返新消息。
     *
     * <p>设计要点:
     * <ul>
     *   <li>群聊使用 {@link GroupMemberCache#getMemberUidList(Long)},返回列表【包含 deFriend=true 的屏蔽成员】,
     *       <b>是有意为之</b> — 屏蔽者取消屏蔽后应能看到屏蔽期间的消息,故也推进其 last_msg_id。
     *       被 {@code removeByGroupId} 物理删除的踢出成员天然不在列表里。</li>
     *   <li>单聊使用 {@link RoomFriend#getUid1()} / {@link RoomFriend#getUid2()} 双端。</li>
     *   <li>数据异常(roomCache 缺 Room / roomFriendDao 缺 RoomFriend / GroupMemberCache 返回 null)时
     *       <b>不能抛异常</b> — 抛了会回滚整个 {@code sendMsg},消息丢失。改为 {@code log.warn} + 防御性返回。</li>
     *   <li>实际 INSERT...ON DUPLICATE KEY UPDATE 在 {@link ContactDao#refreshLastMsgId} 内,
     *       同时覆盖「Contact 行缺失」「last_msg_id 为 NULL」「乱序 msgId」三种场景。</li>
     *   <li>ISS-006: 同步调用 {@link ContactDao#refreshOrCreateActiveTime} 让 sender 的最近会话列表也冒泡。
     *       该方法 {@code @Async},实际异步执行;ISS-004 已为其加 IF 单调保护,与 AckConsumer 异步路径竞态时不回退。</li>
     * </ul>
     */
    private void syncContactLastMsgId(Long roomId, Long msgId) {
        if (roomId == null || msgId == null) {
            return;
        }
        Room room = roomCache.get(roomId);
        if (room == null) {
            log.warn("syncContactLastMsgId: room not found, roomId={}, msgId={}", roomId, msgId);
            return;
        }
        List<Long> memberUidList;
        if (room.isRoomGroup()) {
            memberUidList = groupMemberCache.getMemberUidList(roomId);
            if (CollectionUtil.isEmpty(memberUidList)) {
                log.warn("syncContactLastMsgId: empty group member list, roomId={}, msgId={}", roomId, msgId);
                return;
            }
        } else if (room.isRoomFriend()) {
            RoomFriend roomFriend = roomFriendDao.getByRoomId(roomId);
            if (roomFriend == null) {
                log.warn("syncContactLastMsgId: room_friend not found, roomId={}, msgId={}", roomId, msgId);
                return;
            }
            memberUidList = List.of(roomFriend.getUid1(), roomFriend.getUid2());
        } else {
            // 热点房间(HotRoom)等其他类型不参与 contact 维度的游标
            return;
        }
        contactDao.refreshLastMsgId(roomId, msgId, memberUidList);
        // ISS-006: 推进所有成员(含 sender)的 active_time,让最近会话列表能冒泡。
        // 受 ISS-004 的 IF 单调保护,与 AckConsumer 异步路径竞态时只保留新值,不回退。
        contactDao.refreshOrCreateActiveTime(roomId, memberUidList, msgId, LocalDateTime.now());
    }

    private void checkDeFriend(Boolean isSend, Boolean isTemp, Long roomId, Long uid) {
        Room room = roomCache.get(roomId);
        Assert.notNull(room, "房间不存在!");
        if (room.isRoomGroup()) {
//			GroupMember	member = groupMemberCache.getMemberDetail(roomId, uid);
            GroupMember member = groupMemberDao.getMember(roomId, uid);
            Assert.notNull(member, "您已经被移除该群");
            Assert.isFalse(!isSend && member.getDeFriend(), "您已经屏蔽群聊!");
			if (member.getDeFriend()) {
				throw new BizException("你已屏蔽群聊，无法发送消息");
			}
        } else {
            RoomFriend roomFriend = roomFriendDao.getByRoomId(roomId);
            boolean u1State = uid.equals(roomFriend.getUid1());
            boolean u2State = uid.equals(roomFriend.getUid2());
			if(isTemp){
				// 临时会话的消息校验，整合 UserFriend、RoomFriend 的功能
				UserFriend userFriend = userFriendDao.getByRoomId(roomId, uid);
				if (userFriend == null || !userFriend.getIsTemp()) {
					throw new BizException("当前会话不存在或不是临时会话");
				}

				if (uid.equals(roomFriend.getUid1()) && !userFriend.getTempStatus() && userFriend.getTempMsgCount() >= 1) {
					throw new BizException("对方未回复前只能发送一条打招呼信息");
				}

				if (uid.equals(userFriend.getUid()) && userFriend.getTempMsgCount() >= 5) {
					throw new BizException("临时会话最多只能发送5条消息");
				}
			}

			if (u1State) {
				if (Boolean.TRUE.equals(roomFriend.getDeFriend1())) {
					throw new BizException("你已屏蔽对方，无法发送消息");
				} else if (Boolean.TRUE.equals(roomFriend.getDeFriend2())) {
					throw new BizException("对方已屏蔽你，无法发送消息");
				}
			} else if (u2State) {
				if (Boolean.TRUE.equals(roomFriend.getDeFriend2())) {
					throw new BizException("你已屏蔽对方，无法发送消息");
				} else if (Boolean.TRUE.equals(roomFriend.getDeFriend1())) {
					throw new BizException("对方已屏蔽你，无法发送消息");
				}
			}

            Assert.isTrue(u1State || u2State, "消息已发送, 但对方拒收了");
        }
    }

	/**
	 * @param isSend 来自发送消息的检查
	 * @param skip 是否跳过检查 [远程接口需要]
	 * @param isTemp 临时消息需要跳过部分检查
	 * @param roomId 房间号
	 * @param uid 登录用户id
	 */
    private void check(Boolean isSend, Boolean skip, Boolean isTemp, Long roomId, Long uid) {
        if (skip) {
            return;
        }
        checkDeFriend(isSend, isTemp, roomId, uid);
    }

    @Override
    public ChatMessageResp getMsgResp(Message message, Long receiveUid) {
        return CollUtil.getFirst(getMsgRespBatch(Collections.singletonList(message), receiveUid));
    }

    @Override
    public ChatMessageResp getMsgResp(Long msgId, Long receiveUid) {
        Message msg = messageDao.getById(msgId);
        return getMsgResp(msg, receiveUid);
    }

    @Override
    public CursorPageBaseResp<ChatMessageResp> getMsgPage(ChatMessagePageReq request, Long receiveUid) {
        // 1. 用最后一条消息id，来限制被踢出的人能看见的最大一条消息
        Long lastMsgId = getLastMsgId(request.getRoomId(), receiveUid);
        // 2. 判断我屏蔽会话没有权限
        check(false, request.getSkip(), false, request.getRoomId(), receiveUid);
        CursorPageBaseResp<Message> cursorPage = messageDao.getCursorPage(request.getRoomId(), request, lastMsgId);

        if (cursorPage.isEmpty()) {
            return CursorPageBaseResp.empty();
        }
        return CursorPageBaseResp.init(cursorPage, getMsgRespBatch(cursorPage.getList(), receiveUid), cursorPage.getTotal());
    }

	//	@Cacheable(value = "userRooms", key = "#uid", unless = "#result == null")
	public List<Long> getAccessibleRoomIds(Long uid) {
		// 从群成员缓存和好友关系表中获取有效房间ID
		List<Long> groupRoomIds = groupMemberCache.getJoinedRoomIds(uid);
		List<Long> friendRoomIds = userFriendDao.getAllRoomIdsByUid(uid);

		return Stream.concat(groupRoomIds.stream(), friendRoomIds.stream()).distinct().collect(Collectors.toList());
	}

	/**
	 * 更新会话的最后一条消息ID
	 */
	@Async
	public void updateContactLastMsgIds(Long receiveUid, Map<Long, List<Message>> groupedMessages) {
		// 获取每个房间的最大消息ID
		Map<Long, Long> roomMaxMsgIds = new HashMap<>();
		for (Map.Entry<Long, List<Message>> entry : groupedMessages.entrySet()) {
			Long roomId = entry.getKey();
			List<Message> roomMessages = entry.getValue();

			if (CollUtil.isNotEmpty(roomMessages)) {
				// 找到房间中最大的消息ID
				Long maxMsgId = roomMessages.stream()
						.map(Message::getId)
						.max(Long::compareTo)
						.orElse(null);

				if (maxMsgId != null) {
					roomMaxMsgIds.put(roomId, maxMsgId);
				}
			}
		}

		// 批量更新会话的最后一条消息ID
		if (!roomMaxMsgIds.isEmpty()) {
			List<Long> roomIds = new ArrayList<>(roomMaxMsgIds.keySet());
			List<Contact> contacts = contactDao.get(receiveUid, roomIds);

			for (Contact contact : contacts) {
				Long maxMsgId = roomMaxMsgIds.get(contact.getRoomId());
				if (maxMsgId != null && (contact.getLastMsgId() == null || maxMsgId > contact.getLastMsgId())) {
					contact.setLastMsgId(maxMsgId);
				}
			}

			contactDao.updateBatchById(contacts);
			log.info("已更新{}个会话的最后消息ID", contacts.size());
		}
	}

	@Override
	public List<ChatMessageResp> getMsgList(MsgReq msgReq, Long receiveUid) {
		List<Message> messages;
		if(CollUtil.isEmpty(msgReq.getMsgIds())){
			// 1. 获取用户所有未屏蔽的聊天室ID列表
			List<Long> roomIds = getAccessibleRoomIds(receiveUid);
			if (CollectionUtil.isEmpty(roomIds)) {
				return Collections.emptyList();
			}

			// 2. 如果开启同步功能，那么把最近N天的消息拉回去, 否则获取所有未ack的消息 [要排除屏蔽的房间的消息]
			if(true || msgReq.getAsync()){
				LocalDateTime effectiveStartTime = calculateStartTime(TimeUtils.getTime(LocalDateTime.now().minusDays(14)));
				messages = messageDao.list(new LambdaQueryWrapper<Message>().in(Message::getRoomId, roomIds)
						.between(Message::getCreateTime, effectiveStartTime, LocalDateTime.now()));
			} else {
				// 3. 获取到需要拉取的消息的会话，根据会话拿到最后一个ack的消息id TODO 需要走缓存
				List<Contact> contacts = contactDao.get(receiveUid, roomIds);

				// 查询每个房间中消息id 之后的所有消息
				LambdaQueryWrapper<Message> batchWrapper = new LambdaQueryWrapper<>();
				for (Contact contact : contacts) {
					Long lastMsgId = contact.getLastMsgId() != null ? contact.getLastMsgId() : 0;
					batchWrapper.or(w -> w.eq(Message::getRoomId, contact.getRoomId()).ge(Message::getId, lastMsgId));
				}
				batchWrapper.orderByAsc(Message::getId);
				messages = messageDao.list(batchWrapper);
			}
		} else {
			messages = getMsgByIds(msgReq.getMsgIds());
		}

		Map<Long, List<Message>> groupedMessages = messages.stream().collect(Collectors.groupingBy(Message::getRoomId));

		// 5. 转换为响应对象并返回
		List<ChatMessageResp> baseMessages = new ArrayList<>();
		for (Long roomId : groupedMessages.keySet()) {
			baseMessages.addAll(getMsgRespBatch(groupedMessages.get(roomId), receiveUid));
		}

		// 6. 更新会话的最后一条消息ID [不是查询消息、不是同步数据; 只有是登录获取会话id差额数据的时候才更新会话最后的id]
		if (CollUtil.isEmpty(msgReq.getMsgIds()) && !msgReq.getAsync()) {
			updateContactLastMsgIds(receiveUid, groupedMessages);
		}
		return baseMessages;
	}

	/**
	 * 计算查询的起始时间, 默认最近15天的消息内容
	 */
	private LocalDateTime calculateStartTime(Long lastOptTime) {
		LocalDateTime defaultStartTime = LocalDateTime.now().minusDays(15);

		if (ObjectUtil.isNotNull(lastOptTime) && lastOptTime > 0) {
			LocalDateTime proposedTime = TimeUtils.getDateTimeOfTimestamp(lastOptTime);
			return proposedTime.isAfter(defaultStartTime) ? proposedTime : defaultStartTime;
		}

		return defaultStartTime;
	}

	private Long getLastMsgId(Long roomId, Long receiveUid) {
        Room room = roomCache.get(roomId);
        AssertUtil.isNotEmpty(room, "房间号有误");
        if (room.isHotRoom()) {
            return null;
        }
        AssertUtil.isNotEmpty(receiveUid, "请先登录");
        Contact contact = contactDao.get(receiveUid, roomId);
        return contact.getLastMsgId();
    }

    @Override
    @RedissonLock(key = "#uid")
    public void setMsgMark(Long uid, ChatMessageMarkReq request) {
        AbstractMsgMarkStrategy strategy = MsgMarkFactory.getStrategyNoNull(request.getMarkType());

		// 校验消息
		Message message = msgCache.get(request.getMsgId());
		if (Objects.isNull(message)) {
			return;
		}

		List<Long> uidList = getRoomHowPeople(uid, message.getRoomId());
		switch (MessageMarkActTypeEnum.of(request.getActType())) {
            case MARK:
                strategy.mark(uid, uidList, request.getMsgId());
                break;
            case UN_MARK:
                strategy.unMark(uid, uidList, request.getMsgId());
                break;
        }
    }

    @Override
    public void recallMsg(Long uid, ChatMessageBaseReq request) {
        Message message = messageDao.getById(request.getMsgId());
        //校验能不能执行撤回
		checkRecall(uid, message);
		recallMsgHandler.recall(uid, getRoomHowPeople(uid, message.getRoomId()), message);
    }

	/**
	 * 查询房间中有多少人
	 * @param uid 当前登录用户
	 * @param roomId 房间id
	 * @return
	 */
	private List<Long> getRoomHowPeople(Long uid, Long roomId) {
		// 计算房间有多少人并执行消息撤回
		List<Long> uidList = new ArrayList<>();
		Room room = roomCache.get(roomId);
		if(room.getType().equals(RoomTypeEnum.FRIEND.getType())){
			UserFriend userFriend = userFriendDao.getByRoomId(roomId, uid);
			uidList.add(userFriend.getFriendUid());
			uidList.add(userFriend.getUid());
		} else {
			uidList = groupMemberCache.getMemberExceptUidList(roomId);
		}
		return uidList;
	}

    @Override
    public Collection<MsgReadInfoDTO> getMsgReadInfo(Long uid, ChatMessageReadInfoReq request) {
        List<Message> messages = messageDao.listByIds(request.getMsgIds());
        messages.forEach(message -> AssertUtil.equal(uid, message.getFromUid(), "只能查询自己发送的消息"));
        return contactService.getMsgReadInfo(messages).values();
    }

    @Override
    public CursorPageBaseResp<ChatMessageReadResp> getReadPage(@Nullable Long uid, ChatMessageReadReq request) {
        Message message = messageDao.getById(request.getMsgId());
        AssertUtil.isNotEmpty(message, "消息id有误");
        AssertUtil.equal(uid, message.getFromUid(), "只能查看自己的消息");
        CursorPageBaseResp<Contact> page;
        if (request.getSearchType() == 1) {
            //已读
            page = contactDao.getReadPage(message, request);
        } else {
            page = contactDao.getUnReadPage(message, request);
        }
        if (CollectionUtil.isEmpty(page.getList())) {
            return CursorPageBaseResp.empty();
        }
        return CursorPageBaseResp.init(page, RoomAdapter.buildReadResp(page.getList()), 0L);
    }

    @Async(LUOHUO_EXECUTOR)
    @Override
//    @RedissonLock(key = "#uid")
    public void msgRead(Long uid, ChatMessageMemberReq request) {
        Contact contact = contactDao.get(uid, request.getRoomId());
        if (Objects.nonNull(contact)) {
            Contact update = new Contact();
            update.setId(contact.getId());
            update.setReadTime(LocalDateTime.now());
            contactDao.updateById(update);
        } else {
            log.error("uid --> ", uid, "roomId --> ", request.getRoomId());
//            contactDao.save(uid, request.getRoomId());
        }
    }

    @Override
    public List<Message> getMsgByIds(List<Long> msgIds) {
        return msgCache.getBatch(msgIds).values().stream().sorted(Comparator.comparingLong(Message::getId)).collect(Collectors.toList());
    }

	@Override
	public void createContact(Long uid, Long roomId) {
		contactService.createContact(uid, roomId);
	}

	/**
	 * @return 返回撤回人的权限
	 */
	private void checkRecall(Long uid, Message message) {
        AssertUtil.isNotEmpty(message, "消息有误");
        AssertUtil.notEqual(message.getType(), MessageTypeEnum.RECALL.getType(), "消息无法撤回");

		Room room = roomCache.get(message.getRoomId());
		if(room.getType().equals(RoomTypeEnum.GROUP.getType())){
			GroupMember member = groupMemberCache.getMemberDetail(message.getRoomId(), uid);
			if (member.getRoleId().equals(GroupRoleEnum.LEADER.getType()) || member.getRoleId().equals(GroupRoleEnum.MANAGER.getType())) {
				return;
			}
		}

        boolean self = Objects.equals(uid, message.getFromUid());
        AssertUtil.isTrue(self, "抱歉,您没有权限");
        long between = Duration.between(message.getCreateTime(), LocalDateTime.now()).toMinutes();
        AssertUtil.isTrue(between < 2, "超过2分钟的消息不能撤回");
    }

    public List<ChatMessageResp> getMsgRespBatch(List<Message> messages, Long receiveUid) {
        if (CollectionUtil.isEmpty(messages)) {
            return new ArrayList<>();
        }
        // 查询消息标志
		List<MessageMark> msgMark = messageMarkDao.getValidMarkByMsgIdBatch(messages.stream().map(Message::getId).collect(Collectors.toList()));
		return MessageAdapter.buildMsgResp(messages, msgMark, receiveUid);
    }

}
