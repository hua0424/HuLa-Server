package com.luohuo.flex.im.core.chat.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.model.cache.CacheKey;
import com.luohuo.basic.utils.SpringUtils;
import com.luohuo.flex.common.OnlineService;
import com.luohuo.flex.common.cache.PresenceCacheKeyBuilder;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.dao.RoomGroupDao;
import com.luohuo.flex.im.core.user.dao.UserFriendDao;
import com.luohuo.flex.im.core.user.dao.UserPrivacyDao;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.domain.entity.*;
import com.luohuo.flex.im.domain.enums.*;
import com.luohuo.flex.im.domain.vo.req.room.DisbandGroupReq;
import com.luohuo.flex.im.domain.vo.req.room.GroupMemberPageReq;
import com.luohuo.flex.im.domain.vo.req.room.GroupPageReq;
import com.luohuo.flex.im.domain.vo.req.room.UpdateMemberNicknameReq;
import com.luohuo.flex.im.domain.vo.request.ChatMessageReq;
import com.luohuo.flex.im.domain.vo.request.admin.AdminSetReq;
import com.luohuo.flex.im.domain.vo.request.member.MemberExitReq;
import com.luohuo.flex.im.domain.entity.msg.TextMsgReq;
import com.luohuo.flex.im.domain.vo.res.PageBaseResp;
import com.luohuo.flex.im.domain.vo.resp.room.AiclawMemberResp;
import com.luohuo.flex.im.domain.vo.resp.room.GroupMemberSimpleResp;
import com.luohuo.flex.im.domain.vo.response.GroupResp;
import com.luohuo.flex.im.domain.vo.request.contact.ContactAddReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import com.luohuo.basic.exception.BizException;
import com.luohuo.basic.validator.utils.AssertUtil;
import com.luohuo.flex.im.domain.vo.req.CursorPageBaseReq;
import com.luohuo.flex.im.domain.vo.res.CursorPageBaseResp;
import com.luohuo.flex.im.common.event.GroupMemberAddEvent;
import com.luohuo.flex.im.core.chat.dao.ContactDao;
import com.luohuo.flex.im.core.chat.dao.GroupMemberDao;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.im.domain.dto.RoomBaseInfo;
import com.luohuo.flex.im.domain.vo.request.ChatMessageMemberReq;
import com.luohuo.flex.im.domain.vo.request.ContactFriendReq;
import com.luohuo.flex.im.domain.vo.request.GroupAddReq;
import com.luohuo.flex.im.domain.vo.request.RoomInfoReq;
import com.luohuo.flex.im.domain.vo.request.RoomMyInfoReq;
import com.luohuo.flex.im.domain.vo.request.contact.ContactHideReq;
import com.luohuo.flex.im.domain.vo.request.contact.ContactNotificationReq;
import com.luohuo.flex.im.domain.vo.request.contact.ContactShieldReq;
import com.luohuo.flex.im.domain.vo.request.contact.ContactTopReq;
import com.luohuo.flex.im.domain.vo.request.member.MemberAddReq;
import com.luohuo.flex.im.domain.vo.request.member.MemberDelReq;
import com.luohuo.flex.im.domain.vo.request.member.MemberReq;
import com.luohuo.flex.im.domain.vo.request.room.AnnouncementsParam;
import com.luohuo.flex.im.domain.vo.request.room.ReadAnnouncementsParam;
import com.luohuo.flex.im.domain.vo.request.room.RoomGroupReq;
import com.luohuo.flex.im.domain.vo.response.AnnouncementsResp;
import com.luohuo.flex.im.domain.vo.response.ChatMemberListResp;
import com.luohuo.flex.im.domain.vo.response.ChatRoomResp;
import com.luohuo.flex.im.domain.vo.response.MemberResp;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.core.chat.service.RoomAppService;
import com.luohuo.flex.im.core.chat.service.RoomService;
import com.luohuo.flex.im.core.chat.service.adapter.ChatAdapter;
import com.luohuo.flex.im.core.chat.service.adapter.MessageAdapter;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.HotRoomCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomFriendCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomGroupCache;
import com.luohuo.flex.im.core.chat.service.strategy.msg.AbstractMsgHandler;
import com.luohuo.flex.im.core.chat.service.strategy.msg.MsgHandlerFactory;
import com.luohuo.flex.im.core.user.dao.UserDao;
import com.luohuo.flex.im.domain.vo.req.MergeMessageReq;
import com.luohuo.flex.model.entity.ws.ChatMemberResp;
import com.luohuo.flex.im.core.user.service.FriendService;
import com.luohuo.flex.im.core.user.service.adapter.WsAdapter;
import com.luohuo.flex.im.core.user.service.cache.UserCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 聊天室应用服务实现类（#170 拆分后的 shell）。
 *
 * <p>本类保留 <b>会话/联系人</b>相关能力（createContact、getContactPage、setTop/Hide/Notification/Shield、
 * 会话组装 buildContactResp 等）、系统好友、在线同步 asyncOnline、消息合并 mergeMessage、缓存预热。</p>
 *
 * <p>群成员/群生命周期相关方法已下沉到两个内部 @Service 协作者，本类仍
 * {@code implements RoomAppService}（接口不变、controller 不变），把这些接口方法<b>委派</b>给：</p>
 * <ul>
 *   <li>{@link GroupMembershipManager} —— 加/删成员、管理员、群昵称、成员列表、aiclaw 成员</li>
 *   <li>{@link GroupLifecycleManager} —— 建群/退群/解散、群信息、群列表、公告</li>
 * </ul>
 *
 * <p><b>AOP 代理</b>：委派方法本身<b>不加</b>任何注解（@Transactional/@RedissonLock 随方法迁到对应
 * manager 上），shell→manager 是跨 bean 调用，代理照拆分前一样在 manager 边界生效，不多不少。
 * 依赖方向：shell 依赖两个 manager；manager 不依赖 shell（无环）。</p>
 */
@Slf4j
@Service
@AllArgsConstructor
public class RoomAppServiceImpl implements RoomAppService, InitializingBean {

	private final RoomGroupDao roomGroupDao;
	private final RoomFriendDao roomFriendDao;
	private ContactDao contactDao;
	private RoomCache roomCache;
	private final UserFriendDao userFriendDao;
	private UserPrivacyDao userPrivacyDao;
	private RoomGroupCache roomGroupCache;
	private RoomFriendCache roomFriendCache;
	private CachePlusOps cachePlusOps;
	private UserCache userCache;
	private MessageDao messageDao;
	private HotRoomCache hotRoomCache;
	private UserSummaryCache userSummaryCache;
	private GroupMemberDao groupMemberDao;
	private UserDao userDao;
	private ChatService chatService;
	private RoomService roomService;
	private GroupMemberCache groupMemberCache;
	private PushService pushService;
	private FriendService friendService;
	private OnlineService onlineService;

	// #170 拆出的两个内部协作者
	private final GroupMembershipManager membershipManager;
	private final GroupLifecycleManager lifecycleManager;

	private void warmUpUserRoomCache(Long uid) {
		// 1. 查询房间中所有用户
		List<Long> roomIdList = groupMemberCache.getJoinedRoomIds(uid);
		// 2. 更新 群里与用户的关系
		CacheKey cacheKey = PresenceCacheKeyBuilder.userGroupsKey(uid);
		roomIdList.forEach(roomId -> cachePlusOps.sAdd(cacheKey, roomId));
	}

	/**
	 * 这里需要分页实现，根据情况加载
	 */
	@Override
	public void afterPropertiesSet() {
		// 分页加载所有群
		roomGroupDao.list().parallelStream().map(RoomGroup::getRoomId).forEach(membershipManager::warmUpGroupMemberCache);
		List<Long> roomFriendList = roomFriendDao.list().parallelStream().map(RoomFriend::getRoomId).toList();
		if (CollUtil.isNotEmpty(roomFriendList)) {
			friendService.warmUpRoomMemberCache(roomFriendList);
		}
		// 分页加载所有用户有多少个群的缓存
		List<User> list = userDao.list();
		list.parallelStream().map(User::getId).forEach(this::warmUpUserRoomCache);
	}

	@Override
	public Boolean createContact(Long uid, ContactAddReq request) {
		// 1. 检查对方是否允许临时会话
		UserPrivacy privacy = userPrivacyDao.getByUid(request.getToUid());
		if (privacy != null && (privacy.getIsPrivate() || !privacy.getAllowTempSession())) {
			throw new BizException("对方设置了不接受临时会话");
		}

		// 2. 检查是否已存在临时会话
		UserFriend userFriend = userFriendDao.getByFriend(uid, request.getToUid());
		if (userFriend != null && userFriend.getIsTemp()) {
			throw new BizException("已存在临时会话，请勿重复创建");
		}

		// 创建一个聊天房间
		RoomFriend roomFriend = roomService.createFriendRoom(Arrays.asList(uid, request.getToUid()));
		// 创建双方好友关系
		friendService.createFriend(roomFriend.getRoomId(), uid, request.getToUid());
		// 发送一条临时会话消息
		chatService.sendMsg(MessageAdapter.buildAgreeMsg(roomFriend.getRoomId(), true), uid);
		return true;
	}

	@Override
	public CursorPageBaseResp<ChatRoomResp> getContactPage(CursorPageBaseReq request, Long uid) {
		// 1. 获取登录用户的会话数据
		Double hotEnd = getCursorOrNull(request.getCursor());
		Double hotStart = null;
		// 用户基础会话
		CursorPageBaseResp<Contact> contactPage = contactDao.getContactPage(uid, request);
		HashMap<String, Contact> contactHashMap = contactPage.getList().stream().collect(Collectors.toMap(
				contact -> StrUtil.format("{}_{}", contact.getUid(), contact.getRoomId()),
				contact -> contact, (existing, replacement) -> existing, HashMap::new
		));
		List<Long> baseRoomIds = contactPage.getList().stream().map(Contact::getRoomId).collect(Collectors.toList());
		if (!contactPage.getIsLast()) {
			hotStart = getCursorOrNull(contactPage.getCursor());
		}
		// 热门房间
		Set<ZSetOperations.TypedTuple<Object>> typedTuples = hotRoomCache.getRoomRange(hotStart, hotEnd);
		List<Long> hotRoomIds = typedTuples.stream().map(ZSetOperations.TypedTuple::getValue).filter(Objects::nonNull).map(item -> Long.parseLong(item.toString())).collect(Collectors.toList());
		baseRoomIds.addAll(hotRoomIds);
		// 基础会话和热门房间合并
		CursorPageBaseResp<Long> page = CursorPageBaseResp.init(contactPage, baseRoomIds, 0L);

		// 2. 最后组装会话信息（名称，头像，未读数等）
		List<ChatRoomResp> result = buildContactResp(contactHashMap, uid, page.getList());
		return CursorPageBaseResp.init(page, result, 0L);
	}

	/**
	 * 返回当前登录用户的全部会话
	 *
	 * @param uid
	 * @return
	 */
	@Override
	public List<ChatRoomResp> getContactPage(Long uid) {
		// 1. 查出用户要展示的会话列表
		List<Contact> contacts = contactDao.getAllContactsByUid(uid);

		// 2. 构建会话映射表（uid_roomId -> Contact）
		HashMap<String, Contact> contactMap = contacts.stream()
				.collect(Collectors.toMap(
						contact -> StrUtil.format("{}_{}", contact.getUid(), contact.getRoomId()),
						contact -> contact,
						(existing, replacement) -> existing,
						HashMap::new
				));

		// 3. 提取所有基础会话的 roomId
		List<Long> baseRoomIds = contacts.stream().map(Contact::getRoomId).collect(Collectors.toList());

		// 5. 组装最终会话信息（名称、头像、未读数等）
		return buildContactResp(contactMap, uid, baseRoomIds);
	}

	@Override
	public ChatRoomResp getContactDetail(Long uid, Long roomId) {
		Room room = roomCache.get(roomId);
		AssertUtil.isNotEmpty(room, "房间号有误");
		return buildContactResp(contactDao.getContactMapByUid(uid), uid, Collections.singletonList(roomId)).get(0);
	}

	@Override
	public ChatRoomResp getContactDetailByFriend(Long uid, @Valid ContactFriendReq req) {
		HashMap<String, Contact> contactMap = contactDao.getContactMapByUid(uid);
		if (Objects.equals(req.getRoomType(), RoomTypeEnum.GROUP.getType())) {
			roomService.checkUser(uid, req.getId());
			return buildContactResp(contactMap, uid, Collections.singletonList(req.getId())).get(0);
		}
		RoomFriend friendRoom = roomService.getFriendRoom(uid, req.getId());
		AssertUtil.isNotEmpty(friendRoom, "他不是您的好友");
		return buildContactResp(contactMap, uid, Collections.singletonList(friendRoom.getRoomId())).get(0);
	}

	// ==================== 委派：群列表（GroupLifecycleManager） ====================

	@Override
	public List<MemberResp> groupList(Long uid) {
		return lifecycleManager.groupList(uid);
	}

	@Override
	public List<MemberResp> getAllGroupList() {
		return lifecycleManager.getAllGroupList();
	}

	@Override
	public PageBaseResp<MemberResp> getGroupPage(GroupPageReq req) {
		return lifecycleManager.getGroupPage(req);
	}

	// ==================== 委派：管理员（GroupMembershipManager） ====================

	@Override
	public void addAdmin(Long uid, AdminSetReq request) {
		membershipManager.addAdmin(uid, request);
	}

	@Override
	public void revokeAdmin(Long uid, AdminSetReq request) {
		membershipManager.revokeAdmin(uid, request);
	}

	/**
	 * @param roomId  房间id
	 * @param groupId 群聊id
	 * @param uid     当前人员id
	 */
	@Override
	public void createSystemFriend(Long roomId, Long groupId, Long uid) {
		// 创建会话
		chatService.createContact(uid, roomId);
		// 创建群成员
		roomService.createGroupMember(groupId, uid);
		// 创建系统消息
		friendService.createSystemFriend(uid);
		// 发送进群事件
		CacheKey gKey = PresenceCacheKeyBuilder.groupMembersKey(roomId);
		CacheKey onlineGroupMembersKey = PresenceCacheKeyBuilder.onlineGroupMembersKey(roomId);
		SpringUtils.publishEvent(new GroupMemberAddEvent(this, roomId, Math.toIntExact(cachePlusOps.sCard(gKey)), Math.toIntExact(cachePlusOps.sCard(onlineGroupMembersKey)), Arrays.asList(uid), uid));
	}

	/**
	 * 处理用户在线状态
	 *
	 * @param uidList 需要处理的用户
	 * @param roomId  在某个房间处理
	 */
//	@Async(LUOHUO_EXECUTOR)
	public void asyncOnline(List<Long> uidList, Long roomId, boolean online) {
		Set<Long> onlineList = onlineService.getOnlineUsersList(uidList);
		if (CollUtil.isEmpty(onlineList)) {
			return;
		}

		CacheKey ogmKey = PresenceCacheKeyBuilder.onlineGroupMembersKey(roomId);
		for (Long uid : onlineList) {
			CacheKey ougKey = PresenceCacheKeyBuilder.onlineUserGroupsKey(uid);

			if (online) {
				// 处理在线的状态
				cachePlusOps.sAdd(ogmKey, uid);
				cachePlusOps.sAdd(ougKey, roomId);
			} else {
				// 处理离线的状态
				cachePlusOps.sRem(ougKey, roomId);
				cachePlusOps.sRem(ogmKey, uid);
			}
		}
	}

	// ==================== 委派：群信息维护（GroupLifecycleManager） ====================

	@Override
	public Boolean updateRoomInfo(Long uid, RoomInfoReq request) {
		return lifecycleManager.updateRoomInfo(uid, request);
	}

	@Override
	public Boolean updateMyRoomInfo(Long uid, RoomMyInfoReq request) {
		return lifecycleManager.updateMyRoomInfo(uid, request);
	}

	@Override
	public Boolean setTop(Long uid, ContactTopReq request) {
		// 1.判断会话我有没有
		Contact contact = contactDao.get(uid, request.getRoomId());
		if (ObjectUtil.isNull(contact)) {
			return false;
		}

		// 2.置顶
		contact.setTop(request.getTop());
		return contactDao.updateById(contact);
	}

	// ==================== 委派：公告（GroupLifecycleManager） ====================

	@Override
	public Boolean pushAnnouncement(Long uid, AnnouncementsParam param) {
		return lifecycleManager.pushAnnouncement(uid, param);
	}

	@Override
	public Boolean announcementEdit(Long uid, AnnouncementsParam param) {
		return lifecycleManager.announcementEdit(uid, param);
	}

	@Override
	public IPage<Announcements> announcementList(Long roomId, IPage<Announcements> page) {
		return lifecycleManager.announcementList(roomId, page);
	}

	@Override
	public Boolean readAnnouncement(Long uid, ReadAnnouncementsParam param) {
		return lifecycleManager.readAnnouncement(uid, param);
	}

	@Override
	public AnnouncementsResp getAnnouncement(Long uid, ReadAnnouncementsParam param) {
		return lifecycleManager.getAnnouncement(uid, param);
	}

	@Override
	public Boolean announcementDelete(Long uid, Long id) {
		return lifecycleManager.announcementDelete(uid, id);
	}

	@Override
	public Boolean setHide(Long uid, ContactHideReq req) {
		return contactDao.setHide(uid, req.getRoomId(), req.getHide());
	}

	@Override
	public Boolean setNotification(Long uid, ContactNotificationReq request) {
		// 1. 判断会话我有没有
		Contact contact = contactDao.get(uid, request.getRoomId());
		if (ObjectUtil.isNull(contact)) {
			return false;
		}

		// 2. 修改会话通知类型并通知其他终端
		contact.setMuteNotification(request.getType());

		// 3.通知所有设备我已经开启/关闭这个房间的免打扰
		pushService.sendPushMsg(WsAdapter.buildContactNotification(request), uid, uid);
		return contactDao.updateById(contact);
	}

	@Override
	public Boolean setShield(Long uid, ContactShieldReq request) {
		Contact contact = contactDao.get(uid, request.getRoomId());
		if (ObjectUtil.isNull(contact)) {
			return false;
		}

		String name;
		Room room = roomCache.get(request.getRoomId());
		if (room.getType().equals(RoomTypeEnum.GROUP.getType())) {
			// 1. 把群成员的信息设置为禁止
			name = roomGroupCache.get(request.getRoomId()).getName();
			groupMemberDao.setMemberDeFriend(request.getRoomId(), uid, request.getState());
		} else {
			// 2. 把两个人的房间全部设置为禁止
			RoomFriend roomFriend = roomFriendCache.get(request.getRoomId());
			roomService.updateState(uid.equals(roomFriend.getUid1()), roomFriend.getUid1(), roomFriend.getUid2(), request.getState());

			name = userSummaryCache.get(roomFriend.getUid1().equals(uid) ? roomFriend.getUid2() : roomFriend.getUid1()).getName();
		}

		// 3. 通知所有设备我已经屏蔽这个房间
		pushService.sendPushMsg(WsAdapter.buildShieldContact(request.getState(), name), uid, uid);
		roomFriendCache.delete(request.getRoomId());
		contact.setShield(request.getState());
		return contactDao.updateById(contact);
	}

	@Override
	public void mergeMessage(Long uid, MergeMessageReq req) {
		// 1. 校验人员是否在群里、或者有没有对方的好友
		Room room = roomCache.get(req.getFromRoomId());
		if (ObjectUtil.isNull(room)) {
			throw new BizException("房间不存在");
		}

		if (room.getType().equals(RoomTypeEnum.GROUP.getType())) {
			Triple<RoomGroup, GroupMember, Boolean> permissionCheck = lifecycleManager.checkGroupPermission(uid, req.getFromRoomId());
			if(ObjectUtil.isNull(permissionCheck.getMiddle())) {
				throw new BizException("您不是群成员");
			}
		} else {
			RoomFriend roomFriend = roomFriendCache.get(req.getFromRoomId());
			if (ObjectUtil.isNull(roomFriend) || !roomFriend.getUid1().equals(uid) && !roomFriend.getUid1().equals(uid)) {
				throw new BizException("你们不是好友关系");
			}
		}

		// 2. 当是转发单条消息的时候
		List<Message> messagess = chatService.getMsgByIds(req.getMessageIds());

		// 3. 发布合并消息
		for (Long roomId : req.getRoomIds()) {
			if (req.getType().equals(MergeTypeEnum.SINGLE.getType())) {
				messagess.forEach(message -> {
					ChatMessageReq messageReq = new ChatMessageReq();
					messageReq.setMsgType(message.getType());
					messageReq.setRoomId(roomId);
					// 扩展消息需要另外解析
					messageReq.setBody(analyze(message));
					messageReq.setSkip(true);
					chatService.sendMsg(messageReq, uid);
				});
			} else {
				chatService.sendMsg(MessageAdapter.buildMergeMsg(roomId, messagess), uid);
			}
		}
	}

	/**
	 * 解析转发的消息
	 *
	 * @param message 数据库里面的消息实体
	 */
	private Object analyze(Message message) {
		return switch (MessageTypeEnum.of(message.getType())) {
			case TEXT -> {
				TextMsgReq textMsgReq = new TextMsgReq();
				textMsgReq.setContent(message.getContent());
				textMsgReq.setReplyMsgId(message.getReplyMsgId());
				textMsgReq.setAtUidList(message.getExtra().getAtUidList());
				yield textMsgReq;
			}
			case RECALL -> message.getExtra().getRecall();
			case IMG -> message.getExtra().getImgMsgDTO();
			case FILE -> message.getExtra().getFileMsg();
			case SOUND -> message.getExtra().getSoundMsgDTO();
			case VIDEO -> message.getExtra().getVideoMsgDTO();
			case EMOJI -> message.getExtra().getEmojisMsgDTO();
			case AI, REPLY, AIT, MIXED, BOT, SYSTEM -> null;
			case MERGE -> message.getExtra().getMergeMsgDTO();
			case NOTICE -> message.getExtra().getNoticeMsgDTO();
			case VIDEO_CALL -> message.getExtra().getVideoCallMsgDTO();
			case AUDIO_CALL -> message.getExtra().getAudioCallMsgDTO();
			case LOCATION -> message.getExtra().getMapMsgDTO();
		};
	}

	// ==================== 委派：群查询 / 成员（两个 manager） ====================

	@Override
	public List<RoomGroup> searchGroup(RoomGroupReq req) {
		return lifecycleManager.searchGroup(req);
	}

	@Override
	public MemberResp getGroupDetail(Long uid, Long roomId) {
		return lifecycleManager.getGroupDetail(uid, roomId);
	}

	@Override
	public GroupResp getGroupInfo(Long uid, Long roomId) {
		return lifecycleManager.getGroupInfo(uid, roomId);
	}

	@Override
	public List<ChatMemberResp> listMember(MemberReq request) {
		return membershipManager.listMember(request);
	}

	@Override
	public List<AiclawMemberResp> aiclawListMembers(Long roomId, boolean online, Long aiclawUid) {
		return membershipManager.aiclawListMembers(roomId, online, aiclawUid);
	}

	@Override
	public List<ChatMemberListResp> getMemberList(ChatMessageMemberReq request) {
		return membershipManager.getMemberList(request);
	}

	@Override
	public void delMember(Long uid, MemberDelReq request) {
		membershipManager.delMember(uid, request);
	}

	@Override
	public void addMember(Long uid, MemberAddReq request) {
		membershipManager.addMember(uid, request);
	}

	@Override
	public Long addGroup(Long uid, GroupAddReq request) {
		return lifecycleManager.addGroup(uid, request);
	}

	@Override
	public void exitGroup(Boolean isGroup, Long uid, MemberExitReq request) {
		lifecycleManager.exitGroup(isGroup, uid, request);
	}

	@Override
	public PageBaseResp<GroupMemberSimpleResp> getGroupMemberPage(GroupMemberPageReq request) {
		return membershipManager.getGroupMemberPage(request);
	}

	@Override
	public void updateMemberNickname(UpdateMemberNicknameReq request) {
		membershipManager.updateMemberNickname(request);
	}

	@Override
	public void disbandGroup(DisbandGroupReq request) {
		lifecycleManager.disbandGroup(request);
	}

	private Double getCursorOrNull(String cursor) {
		if (StringUtils.isEmpty(cursor)) {
			return null;
		}
		return Optional.of(cursor).map(Double::parseDouble).orElse(null);
	}

	/**
	 * @param contactMap 会话映射
	 * @param uid        当前登录的用户
	 * @param roomIds    所有会话对应的房间
	 * @return
	 */
	@NotNull
	private List<ChatRoomResp> buildContactResp(HashMap<String, Contact> contactMap, Long uid, List<Long> roomIds) {
		// 表情和头像
		Map<Long, RoomBaseInfo> roomBaseInfoMap = getRoomBaseInfoMap(roomIds, uid);
		// 最后一条消息
		List<Long> msgIds = roomBaseInfoMap.values().stream().map(RoomBaseInfo::getLastMsgId).collect(Collectors.toList());
		List<Message> messages = CollectionUtil.isEmpty(msgIds) ? new ArrayList<>() : messageDao.listByIds(msgIds);
		Map<Long, Message> msgMap = messages.stream().collect(Collectors.toMap(Message::getId, Function.identity()));
		// 消息未读数
		Map<Long, Integer> unReadCountMap = getUnReadCountMap(uid, contactMap.values());

		return roomBaseInfoMap.values().stream().map(room -> {
					ChatRoomResp resp = new ChatRoomResp();
					Long roomId = room.getRoomId();
					RoomBaseInfo roomBaseInfo = roomBaseInfoMap.get(roomId);
					Contact contact = contactMap.get(StrUtil.format("{}_{}", uid, roomId));
					if (ObjectUtil.isNotNull(contact)) {
						resp.setId(contact.getId());
						resp.setHide(contact.getHide());
						resp.setShield(contact.getShield());
						resp.setMuteNotification(contact.getMuteNotification());
						resp.setTop(contact.getTop());
					} else {
						// ISS-009: contact 行不存在(热门房间合并/数据缺失场景),走只读默认值,
						// 避免下方 resp.setId(contact.getId()) / contact.getShield() 触发 NPE
						resp.setHide(true);
						resp.setShield(true);
						resp.setMuteNotification(2);
						resp.setTop(false);
					}
					resp.setDetailId(room.getId());
					resp.setAvatar(roomBaseInfo.getAvatar());
					resp.setRoomId(roomId);
					resp.setAccount(room.getAccount());
					resp.setActiveTime(room.getActiveTime());
					resp.setHotFlag(roomBaseInfo.getHotFlag());
					resp.setType(roomBaseInfo.getType());
					resp.setName(roomBaseInfo.getName());
					resp.setOperate(roomBaseInfo.getRoleId());
					resp.setRemark(roomBaseInfo.getRemark());
					resp.setMyName(roomBaseInfo.getMyName());
					Message message = msgMap.get(room.getLastMsgId());
					if (resp.getShield()) {
						resp.setText("您已屏蔽该会话");
					} else {
						if (Objects.nonNull(message)) {
							AbstractMsgHandler strategyNoNull = MsgHandlerFactory.getStrategyNoNull(message.getType());
							// 判断是群聊还是单聊
							if (Objects.equals(roomBaseInfo.getType(), RoomTypeEnum.GROUP.getType())) {
								resp.setText(strategyNoNull.showContactMsg(message));
								GroupMember messageUser = groupMemberCache.getMemberDetail(roomId, message.getFromUid());
								if (ObjectUtil.isNotNull(messageUser)) {
									// 当自己查看时，且最后一条消息是自己发送的，那么显示群备注
									if (uid.equals(message.getFromUid()) && StrUtil.isNotEmpty(messageUser.getRemark())) {
										resp.setRemark(messageUser.getRemark());
									}
								}
							} else {
								resp.setText(strategyNoNull.showContactMsg(message));
							}
						}
					}
					resp.setUnreadCount(Boolean.TRUE.equals(resp.getShield()) ? 0 : unReadCountMap.getOrDefault(roomId, 0));
					return resp;
				}).sorted(Comparator.comparing(ChatRoomResp::getActiveTime).reversed())
				.collect(Collectors.toList());
	}

	/**
	 * 获取未读数
	 */
	public Map<Long, Integer> getUnReadCountMap(Long uid, Collection<Contact> contactList) {
		if (CollUtil.isEmpty(contactList)) {
			return new HashMap<>();
		}

		return messageDao.batchGetUnReadCount(uid, contactList);
	}

	/**
	 * 返回房间id与好友的映射
	 *
	 * @param roomIds
	 * @param uid
	 * @return
	 */
	private Map<Long, User> getFriendRoomMap(List<Long> roomIds, Long uid) {
		if (CollectionUtil.isEmpty(roomIds)) {
			return new HashMap<>();
		}
		Map<Long, RoomFriend> roomFriendMap = roomFriendCache.getBatch(roomIds);
		Set<Long> friendUidSet = ChatAdapter.getFriendUidSet(roomFriendMap.values(), uid);
		Map<Long, User> userBatch = userCache.getBatch(new ArrayList<>(friendUidSet));
		// ISS-009: 好友账号被逻辑删除(im_user.is_del=1)时 userCache 不会返回该 user,
		// 这里若仍走 Collectors.toMap 会因 valueMapper 返回 null 触发 JDK Objects.requireNonNull NPE。
		// 改为手工 put,跳过已删好友,由 getRoomBaseInfoMap 走"账号已注销"占位分支兜底。
		Map<Long, User> result = new HashMap<>(roomFriendMap.size());
		roomFriendMap.values().forEach(roomFriend -> {
			Long friendUid = ChatAdapter.getFriendUid(roomFriend, uid);
			User user = userBatch.get(friendUid);
			if (user != null) {
				result.put(roomFriend.getRoomId(), user);
			}
		});
		return result;
	}

	private Map<Long, RoomBaseInfo> getRoomBaseInfoMap(List<Long> roomIds, Long uid) {
		Map<Long, Room> roomMap = roomCache.getBatch(roomIds);
		// 房间根据好友和群组类型分组
		Map<Integer, List<Long>> groupRoomIdMap = roomMap.values().stream().filter(Objects::nonNull).collect(Collectors.groupingBy(Room::getType, Collectors.mapping(Room::getId, Collectors.toList())));
		// 获取群组信息
		List<Long> groupRoomId = groupRoomIdMap.get(RoomTypeEnum.GROUP.getType());
		Map<Long, RoomGroup> roomInfoBatch = roomGroupCache.getBatch(groupRoomId);
		// 获取好友信息
		List<Long> friendRoomId = groupRoomIdMap.get(RoomTypeEnum.FRIEND.getType());
		Map<Long, User> friendRoomMap = getFriendRoomMap(friendRoomId, uid);
		Map<Long, RoomBaseInfo> collect = roomMap.values().stream().filter(Objects::nonNull).map(room -> {
			RoomBaseInfo roomBaseInfo = new RoomBaseInfo();
			roomBaseInfo.setRoomId(room.getId());
			roomBaseInfo.setType(room.getType());
			roomBaseInfo.setHotFlag(room.getHotFlag());
			roomBaseInfo.setLastMsgId(room.getLastMsgId());
			roomBaseInfo.setActiveTime(room.getActiveTime());
			if (RoomTypeEnum.of(room.getType()) == RoomTypeEnum.GROUP) {
				RoomGroup roomGroup = roomInfoBatch.get(room.getId());
				// ISS-009: roomGroupCache 缺失(数据不一致)时走"群组已解散"占位,避免下方无条件取值 NPE
				if (ObjectUtil.isNull(roomGroup)) {
					roomBaseInfo.setId(0L);
					roomBaseInfo.setAvatar("");
					roomBaseInfo.setAccount("");
					roomBaseInfo.setName("群组已解散");
					roomBaseInfo.setMyName("群组已解散");
					roomBaseInfo.setRemark("群组已解散");
					roomBaseInfo.setRoleId(0);
				} else {
					roomBaseInfo.setId(roomGroup.getId());
					roomBaseInfo.setAvatar(roomGroup.getAvatar());
					roomBaseInfo.setAccount(roomGroup.getAccount());
					GroupMember member = groupMemberCache.getMemberDetail(room.getId(), uid);
					// todo 稳定了这里可以不用判空，理论上100% 在群里
					if (ObjectUtil.isNotNull(member)) {
						roomBaseInfo.setMyName(member.getMyName());
						roomBaseInfo.setRemark(member.getRemark());
						roomBaseInfo.setName(roomGroup.getName());
						roomBaseInfo.setRoleId(member.getRoleId());
					} else {
						roomBaseInfo.setName("会话异常");
						roomBaseInfo.setMyName("会话异常");
						roomBaseInfo.setRemark("会话异常");
						roomBaseInfo.setRoleId(0);
					}
				}
			} else if (RoomTypeEnum.of(room.getType()) == RoomTypeEnum.FRIEND) {
				User user = friendRoomMap.get(room.getId());
				// ISS-009: getFriendRoomMap 已跳过已注销好友,这里走占位分支避免后续无条件取值 NPE
				if (ObjectUtil.isNotNull(user)) {
					roomBaseInfo.setId(user.getId());
					roomBaseInfo.setRoleId(0);
					roomBaseInfo.setName(user.getName());
					roomBaseInfo.setAvatar(user.getAvatar());
					roomBaseInfo.setAccount(user.getAccount());
				} else {
					roomBaseInfo.setId(0L);
					roomBaseInfo.setRoleId(0);
					roomBaseInfo.setName("账号已注销");
					roomBaseInfo.setAvatar("");
					roomBaseInfo.setAccount("");
				}
			}
			return roomBaseInfo;
		}).collect(Collectors.toMap(RoomBaseInfo::getRoomId, Function.identity()));

		return collect;
	}
}
