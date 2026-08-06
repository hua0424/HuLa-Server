package com.luohuo.flex.im.core.chat.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.exception.code.ResponseEnum;
import com.luohuo.basic.model.cache.CacheKey;
import com.luohuo.basic.utils.SpringUtils;
import com.luohuo.basic.utils.TimeUtils;
import com.luohuo.flex.common.OnlineService;
import com.luohuo.flex.common.cache.PresenceCacheKeyBuilder;
import com.luohuo.flex.common.constant.DefValConstants;
import com.luohuo.flex.im.core.chat.dao.RoomGroupDao;
import com.luohuo.flex.im.core.user.dao.UserBackpackDao;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.domain.dto.SummeryInfoDTO;
import com.luohuo.flex.im.domain.entity.*;
import com.luohuo.flex.im.domain.enums.*;
import com.luohuo.flex.im.domain.vo.req.room.DisbandGroupReq;
import com.luohuo.flex.im.domain.vo.req.room.GroupPageReq;
import com.luohuo.flex.im.domain.vo.request.ChatMessageReq;
import com.luohuo.flex.im.domain.vo.request.member.MemberExitReq;
import com.luohuo.flex.im.domain.vo.res.PageBaseResp;
import com.luohuo.flex.im.domain.vo.response.GroupResp;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.luohuo.basic.exception.code.GroupErrorEnum;
import com.luohuo.basic.validator.utils.AssertUtil;
import com.luohuo.flex.model.redis.annotation.RedissonLock;
import com.luohuo.flex.im.common.event.GroupMemberAddEvent;
import com.luohuo.flex.im.core.chat.dao.ContactDao;
import com.luohuo.flex.im.core.chat.dao.GroupMemberDao;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.im.domain.vo.request.GroupAddReq;
import com.luohuo.flex.im.domain.vo.request.RoomInfoReq;
import com.luohuo.flex.im.domain.vo.request.RoomMyInfoReq;
import com.luohuo.flex.im.domain.vo.request.room.AnnouncementsParam;
import com.luohuo.flex.im.domain.vo.request.room.ReadAnnouncementsParam;
import com.luohuo.flex.im.domain.vo.request.room.RoomGroupReq;
import com.luohuo.flex.im.domain.vo.response.AnnouncementsResp;
import com.luohuo.flex.im.domain.vo.response.MemberResp;
import com.luohuo.flex.im.domain.vo.response.ReadAnnouncementsResp;
import com.luohuo.flex.im.core.chat.service.AiclawParticipant;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.core.chat.service.RoomService;
import com.luohuo.flex.im.core.chat.mapper.AiclawGroupConfigMapper;
import com.luohuo.flex.im.core.chat.mapper.AiclawThinkingMapper;
import com.luohuo.flex.im.core.chat.mapper.AiclawThinkingMsgRelMapper;
import com.luohuo.flex.im.core.chat.service.adapter.MemberAdapter;
import com.luohuo.flex.im.core.chat.service.adapter.MessageAdapter;
import com.luohuo.flex.im.core.chat.service.adapter.RoomAdapter;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.WSMemberChange;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomAnnouncementsCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomGroupCache;
import com.luohuo.flex.im.core.user.service.cache.UserCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 群组生命周期管理器（从 {@link RoomAppServiceImpl} 拆出，#170）。
 * <p>负责：建群/退群/解散群、群信息维护、群列表查询、公告管理。</p>
 *
 * <p><b>AOP 代理注意</b>：{@link #exitGroup} 携带 {@link RedissonLock}，历史上被
 * {@code delMember}、{@code disbandGroup} 及自身递归<b>自调用</b>，Spring 代理在自调用时被绕过
 * （不新增锁）。为字节级保持该行为，退群核心逻辑抽到<b>无注解</b>的包级私有方法
 * {@link #exitGroupInternal}；所有<b>内部</b>调用方（本类的 disbandGroup/递归、跨 bean 的
 * GroupMembershipManager.delMember）一律走 core，仅 controller 经 shell 跨 bean 调用公开的
 * {@link #exitGroup} 时才应用代理（与拆分前一致）。</p>
 */
@Slf4j
@Service
@AllArgsConstructor
public class GroupLifecycleManager {

	private RoomCache roomCache;
	private RoomGroupCache roomGroupCache;
	private final RoomGroupDao roomGroupDao;
	private CachePlusOps cachePlusOps;
	private UserCache userCache;
	private MessageDao messageDao;
	private final UserBackpackDao userBackpackDao;
	private UserSummaryCache userSummaryCache;
	private RoomAnnouncementsCache roomAnnouncementsCache;
	private GroupMemberDao groupMemberDao;
	private ChatService chatService;
	private RoomService roomService;
	private GroupMemberCache groupMemberCache;
	private PushService pushService;
	private OnlineService onlineService;
	private TransactionTemplate transactionTemplate;
	private ContactDao contactDao;
	private final AiclawParticipant aiclawParticipant;
	private final AiclawGroupConfigMapper aiclawGroupConfigMapper;
	private final AiclawThinkingMapper aiclawThinkingMapper;
	private final AiclawThinkingMsgRelMapper aiclawThinkingMsgRelMapper;
	private final PresenceSyncHelper presenceSyncHelper;

	public List<MemberResp> groupList(Long uid) {
		List<MemberResp> voList = roomService.groupList(uid);
		// #99: 无群用户 voList 为空 → groupIdList 为空 → getGroupMemberByGroupIdListAndUid 的
		// MyBatis foreach 生成非法 `IN ()` → SQL 语法错 → 全局兜底「系统繁忙」。空集提前返回
		// （同 getGroupPage 的 isEmpty 守卫），无群用户得到空列表而非 500。
		if (voList.isEmpty()) {
			return voList;
		}
		Set<Long> groupIdList = voList.stream().map(MemberResp::getGroupId).collect(Collectors.toSet());
		List<Long> roomIdList = voList.stream().map(MemberResp::getRoomId).collect(Collectors.toList());

		Map<Long, Long> onlineMap = onlineService.getBatchGroupOnlineCounts(roomIdList);
		List<GroupMember> members = groupMemberDao.getGroupMemberByGroupIdListAndUid(uid, groupIdList);
		Map<Long, GroupMember> map = members.stream().collect(Collectors.toMap(GroupMember::getGroupId, Function.identity()));

		// 渲染群信息
		voList.forEach(item -> {
			Long memberNum = (long) groupMemberCache.getMemberUidList(item.getRoomId()).size();
			GroupMember member = map.get(item.getGroupId());

			item.setOnlineNum(onlineMap.get(item.getRoomId()));
			item.setRoleId(GroupRoleAPPEnum.of(member.getRoleId()).getType());
			item.setMemberNum(memberNum);
			item.setRemark(member.getRemark());
			item.setMyName(member.getMyName());
		});
		return voList;
	}

	public List<MemberResp> getAllGroupList() {
		List<MemberResp> voList = roomService.getAllGroupList();
		List<Long> roomIdList = voList.stream().map(MemberResp::getRoomId).collect(Collectors.toList());

		Map<Long, Long> onlineMap = onlineService.getBatchGroupOnlineCounts(roomIdList);

		// 渲染群信息
		voList.forEach(item -> {
			Long memberNum = (long) groupMemberCache.getMemberUidList(item.getRoomId()).size();
			item.setOnlineNum(onlineMap.get(item.getRoomId()));
			item.setMemberNum(memberNum);
		});
		return voList;
	}

	public PageBaseResp<MemberResp> getGroupPage(GroupPageReq req) {
		PageBaseResp<MemberResp> pageResp = roomService.getGroupPage(req);
		List<Long> roomIdList = pageResp.getList().stream().map(MemberResp::getRoomId).collect(Collectors.toList());

		if (roomIdList.isEmpty()) {
			return pageResp;
		}

		Map<Long, Long> onlineMap = onlineService.getBatchGroupOnlineCounts(roomIdList);

		// 渲染群信息（管理员查看，不需要个人备注和群昵称）
		pageResp.getList().forEach(item -> {
			Long memberNum = (long) groupMemberCache.getMemberUidList(item.getRoomId()).size();
			item.setOnlineNum(onlineMap.get(item.getRoomId()));
			item.setMemberNum(memberNum);
		});
		return pageResp;
	}

	/**
	 * 群主，管理员才可以修改
	 */
	public Boolean updateRoomInfo(Long uid, RoomInfoReq request) {
		// 1.校验修改权限
		Triple<RoomGroup, GroupMember, Boolean> permissionCheck = checkGroupPermission(uid, request.getId());
		if (!permissionCheck.getRight()) {
			log.warn("用户无权限修改群信息，uid:{}, roomId:{}", uid, request.getId());
			return false;
		}
		RoomGroup roomGroup = permissionCheck.getLeft();

		// 2.修改群信息
		roomGroup.setAvatar(request.getAvatar());
		roomGroup.setName(request.getName());
		roomGroup.setAllowScanEnter(request.getAllowScanEnter());

		// 3. 通知群里所有人群信息修改了
		Boolean success = transactionTemplate.execute(status -> {
			try {
				boolean updateResult = roomService.updateRoomInfo(roomGroup);
				if (!updateResult) {
					status.setRollbackOnly();
					return false;
				}

				// 4. 异步清理缓存
				CompletableFuture.runAsync(() -> {
					roomGroupCache.delete(roomGroup.getRoomId());
					roomGroupCache.evictGroup(roomGroup.getAccount());
				});

				List<Long> memberUidList = groupMemberCache.getMemberExceptUidList(roomGroup.getRoomId());
				pushService.sendPushMsg(RoomAdapter.buildRoomGroupChangeWS(roomGroup.getRoomId(), roomGroup.getName(), roomGroup.getAvatar()), memberUidList, uid);
				return true;
			} catch (Exception e) {
				status.setRollbackOnly();
				throw e;
			}
		});

		return success;
	}

	public Boolean updateMyRoomInfo(Long uid, RoomMyInfoReq request) {
		// 1.校验修改权限
		Triple<RoomGroup, GroupMember, Boolean> permissionCheck = checkGroupPermission(uid, request.getId());
		RoomGroup roomGroup = permissionCheck.getLeft();
		GroupMember member = permissionCheck.getMiddle();

		// 2.修改我的信息
		boolean equals = member.getMyName().equals(StrUtil.isEmpty(request.getMyName()) ? "" : request.getMyName());
		boolean success = groupMemberDao.update(null, Wrappers.<GroupMember>lambdaUpdate()
				.set(GroupMember::getRemark, request.getRemark())
				.set(GroupMember::getMyName, request.getMyName())
				.eq(GroupMember::getId, member.getId()));

		groupMemberCache.evictMemberDetail(roomGroup.getRoomId(), uid);

		// 3.通知群里所有人我的信息改变了
		if (!equals && success) {
			List<Long> memberUidList = groupMemberCache.getMemberExceptUidList(roomGroup.getRoomId());
			pushService.sendPushMsg(RoomAdapter.buildMyRoomGroupChangeWS(roomGroup.getRoomId(), uid, request.getMyName()), memberUidList, uid);
		}
		return success;
	}

	/**
	 * 统一权限校验方法
	 * @return 返回三元组(roomGroup, groupMember, hasPermission)
	 */
	public Triple<RoomGroup, GroupMember, Boolean> checkGroupPermission(Long uid, Long roomId) {
		if(ContextUtil.getSystemType().equals("1")){
			return Triple.of(roomGroupCache.get(roomId), null, true);
		}
		RoomGroup roomGroup = roomGroupCache.get(roomId);
		if (roomGroup == null) {
			return Triple.of(null, null, false);
		}

		GroupMember groupMember = groupMemberCache.getMemberDetail(roomId, uid);
		if (groupMember == null) {
			return Triple.of(roomGroup, null, false);
		}

		boolean hasPermission = !GroupRoleEnum.MEMBER.getType().equals(groupMember.getRoleId());
		return Triple.of(roomGroup, groupMember, hasPermission);
	}

	@Transactional
	public Boolean pushAnnouncement(Long uid, AnnouncementsParam param) {
		// 1. 权限校验
		Triple<RoomGroup, GroupMember, Boolean> permissionCheck = checkGroupPermission(uid, param.getRoomId());
		if (!permissionCheck.getRight()) {
			return false;
		}

		// 2. 保存公告
		List<Long> uids = roomService.getGroupUsers(permissionCheck.getLeft().getId(), false);
		if (CollUtil.isNotEmpty(uids)) {
			LocalDateTime now = LocalDateTime.now();
			Announcements announcements = new Announcements();
			announcements.setContent(param.getContent());
			announcements.setRoomId(param.getRoomId());
			announcements.setUid(uid);
			announcements.setTop(param.getTop());
			announcements.setCreateTime(now);
			announcements.setUpdateTime(now);
			roomService.saveAnnouncements(announcements);

			// 创建已读的信息
			List<AnnouncementsReadRecord> announcementsReadRecordList = new ArrayList<>();
			uids.forEach(item -> {
				AnnouncementsReadRecord readRecord = new AnnouncementsReadRecord();
				readRecord.setAnnouncementsId(announcements.getId());
				readRecord.setUid(item);
				readRecord.setIsCheck(false);
				readRecord.setCreateBy(uid);
				announcementsReadRecordList.add(readRecord);
			});
			// 批量添加未读消息
			Boolean saved = roomService.saveBatchAnnouncementsRecord(announcementsReadRecordList);
			if (saved) {
				// 发送公告消息、推送群成员公告内容
				chatService.sendMsg(MessageAdapter.buildAnnouncementsMsg(param.getRoomId(), announcements), uid);
			}
			return saved;
		}
		return false;
	}

	public Boolean announcementEdit(Long uid, AnnouncementsParam param) {
		// 1. 鉴权
		Triple<RoomGroup, GroupMember, Boolean> permissionCheck = checkGroupPermission(uid, param.getRoomId());
		if (!permissionCheck.getRight()) {
			return false;
		}
		RoomGroup roomGroup = permissionCheck.getLeft();

		// 2. 置顶公告
		List<Long> uids = roomService.getGroupUsers(roomGroup.getId(), false);
		if (CollUtil.isNotEmpty(uids)) {
			AnnouncementsResp announcement = roomService.getAnnouncement(param.getId());
			if (ObjectUtil.isNull(announcement)) {
				return false;
			}
			Announcements announcements = new Announcements();
			announcements.setId(param.getId());
			announcements.setRoomId(param.getRoomId());
			announcements.setContent(param.getContent());
			announcements.setTop(param.getTop());
			announcements.setUpdateTime(TimeUtils.now());
			Boolean edit = roomService.updateAnnouncement(announcements);
			if (edit) {
				chatService.sendMsg(MessageAdapter.buildAnnouncementsMsg(param.getRoomId(), announcements), uid);
			}
			return edit;
		}
		return false;
	}

	public IPage<Announcements> announcementList(Long roomId, IPage<Announcements> page) {
		return roomService.announcementList(roomId, page);
	}

	public Boolean readAnnouncement(Long uid, ReadAnnouncementsParam param) {
		// 1.更新已读状态
		Boolean success = roomService.readAnnouncement(uid, param.getAnnouncementId());

		if (success) {
			// 2.刷新最新的已读数量，通知所有人有人对 announcementId 已读了
			roomAnnouncementsCache.add(param.getAnnouncementId(), uid);

			List<Long> memberUidList = groupMemberCache.getMemberExceptUidList(param.getRoomId());
			pushService.sendPushMsg(MessageAdapter.buildReadRoomGroupAnnouncement(new ReadAnnouncementsResp(uid, roomAnnouncementsCache.get(param.getAnnouncementId()))), memberUidList, uid);
		}
		return success;
	}

	public AnnouncementsResp getAnnouncement(Long uid, ReadAnnouncementsParam param) {
		// 1.鉴权
		roomService.checkUser(uid, param.getRoomId());

		// 2.获取公告
		AnnouncementsResp announcement = roomService.getAnnouncement(param.getAnnouncementId());

		// 3.查询公告已读数量
		Long count = roomAnnouncementsCache.get(param.getAnnouncementId());
		if (count < 1) {
			count = roomService.getAnnouncementReadCount(param.getAnnouncementId());
			roomAnnouncementsCache.load(Arrays.asList(param.getAnnouncementId()));
		}

		// todo 需要测试看看重新加载公告已读数量对不对
		announcement.setCount(count);
		return announcement;
	}

	@RedissonLock(prefixKey = "announceDel:", key = "#id")
	public Boolean announcementDelete(Long uid, Long id) {
		// 1. 鉴权
		AnnouncementsResp resp = roomService.getAnnouncement(id);
		Triple<RoomGroup, GroupMember, Boolean> validation = checkGroupPermission(uid, resp.getRoomId());
		GroupMember groupMember = validation.getMiddle();

		long count = userBackpackDao.countByUidAndItemId(uid, DefValConstants.CONTRIBUTOR_ID);
		if (count == 0 && GroupRoleEnum.MEMBER.getType().equals(groupMember.getRoleId())) {
			return false;
		}

		return roomService.announcementDelete(id);
	}

	public List<RoomGroup> searchGroup(RoomGroupReq req) {
		return roomGroupCache.searchGroup(req.getAccount());
	}

	public MemberResp getGroupDetail(Long uid, Long roomId) {
		RoomGroup roomGroup = roomGroupCache.get(roomId);
		Room room = roomCache.get(roomId);
		AssertUtil.isNotEmpty(roomGroup, "roomId有误");

		Map<Long, Long> map = onlineService.getBatchGroupOnlineCounts(Arrays.asList(room.getId()));

		// 获取群成员数、在线人员、备注、我的群名称
		Long onlineNum = map.get(room.getId());
		Long memberNum = (long) groupMemberCache.getMemberUidList(roomId).size();
		GroupMember member = groupMemberDao.getMemberByGroupId(roomGroup.getId(), uid);

		return MemberResp.builder()
				.avatar(roomGroup.getAvatar())
				.roomId(roomId)
				.groupName(roomGroup.getName())
				.onlineNum(onlineNum)
				.memberNum(memberNum)
				.account(roomGroup.getAccount())
				.remark(member == null? "": member.getRemark())
				.myName(member == null? "": member.getMyName())
				.allowScanEnter(roomGroup.getAllowScanEnter())
				.roleId(getGroupRole(uid, roomGroup.getId()))
				.build();
	}

	public GroupResp getGroupInfo(Long uid, Long roomId) {
		return roomGroupDao.getByRoomIdIgnoreDel(roomId);
	}

	/**
	 * 退出群聊 | 解散群聊（公开入口，携带 @RedissonLock）。
	 *
	 * <p>见类注释：仅 controller 经 shell 跨 bean 调用此入口时代理才生效并加锁；
	 * 所有内部调用方走 {@link #exitGroupInternal}。</p>
	 *
	 * @param uid     需要退出的用户ID
	 * @param request 请求信息
	 */
	@RedissonLock(prefixKey = "exitGroup:", key = "#request.roomId")
	public void exitGroup(Boolean isGroup, Long uid, MemberExitReq request) {
		exitGroupInternal(isGroup, uid, request);
	}

	/**
	 * 退群/解散核心逻辑（无注解，包级私有）。历史上 exitGroup 被自调用时代理被绕过（不加锁），
	 * 此 core 即那条「无代理」路径：本类 disbandGroup/递归、以及跨 bean 的
	 * GroupMembershipManager.delMember 均调用它，保持拆分前「内部调用不新增锁」的语义。
	 */
	void exitGroupInternal(Boolean isGroup, Long uid, MemberExitReq request) {
		Long roomId = request.getRoomId();
		// 1. 判断群聊是否存在
		RoomGroup roomGroup = roomGroupCache.getByRoomIdFromDb(roomId);
		AssertUtil.isNotEmpty(roomGroup, GroupErrorEnum.GROUP_NOT_EXIST);

		// 2. 判断房间是否是大群聊 （大群聊禁止退出）
		Room room = roomService.getById(roomId);
		AssertUtil.isFalse(room.isHotRoom(), GroupErrorEnum.NOT_ALLOWED_FOR_EXIT_GROUP);

		// 3. 判断群成员是否在群中
		Boolean isGroupShip = groupMemberDao.isGroupShip(roomGroup.getRoomId(), Collections.singletonList(uid));
		AssertUtil.isTrue(isGroupShip, GroupErrorEnum.USER_NOT_IN_GROUP);

		// 5. 获取要移除的群成员
		Boolean isLord = groupMemberDao.isLord(roomGroup.getId(), uid);
		List<Long> memberUidList;
		if (isLord) {
			memberUidList = groupMemberDao.getMemberUidList(roomGroup.getId(), null);
		} else {
			memberUidList = groupMemberCache.getMemberExceptUidList(roomGroup.getRoomId());
		}
		CacheKey gKey = PresenceCacheKeyBuilder.groupMembersKey(room.getId());
		if (isGroup || isLord) {
			User user = userCache.get(uid);
			ChatMessageReq messageReq = new ChatMessageReq();
			messageReq.setBody(StrUtil.format("{}解散了群聊", user.getName()));
			messageReq.setMsgType(MessageTypeEnum.SYSTEM.getType());
			messageReq.setSkip(true);
			messageReq.setRoomId(roomId);
			chatService.sendMsg(messageReq, uid);

			// 4.1 删除房间和群并清除缓存
			transactionTemplate.execute(e -> {
				boolean isDelRoom = roomService.removeById(roomId);
				roomGroupCache.removeById(roomGroup.getId());
				roomGroupCache.evictGroup(roomGroup.getAccount());
				if (StrUtil.isNotEmpty(request.getAccount())) {
					roomGroupCache.evictGroup(request.getAccount());
				}
				AssertUtil.isTrue(isDelRoom, ResponseEnum.SYSTEM_BUSY.getMsg());
				// 4.2 删除会话
				Boolean isDelContact = contactDao.removeByRoomId(roomId, Collections.EMPTY_LIST);
				AssertUtil.isTrue(isDelContact, "会话移除异常");
				// 4.3 删除群成员
				Boolean isDelGroupMember = groupMemberDao.removeByGroupId(roomGroup.getId(), Collections.EMPTY_LIST);
				AssertUtil.isTrue(isDelGroupMember, "群成员移除失败");
				// 4.4 删除消息记录 (逻辑删除)
				Boolean isDelMessage = messageDao.removeByRoomId(roomId, Collections.EMPTY_LIST);
				AssertUtil.isTrue(isDelMessage, ResponseEnum.SYSTEM_BUSY.getMsg());
				// 4.5 #182: 解散时清理 aiclaw 扩展表，避免按 aiclaw 维度累积脏数据
				aiclawGroupConfigMapper.deleteByRoomId(roomId);
				aiclawThinkingMsgRelMapper.deleteByRoomId(roomId);
				aiclawThinkingMapper.logicDeleteByRoomId(roomId);
				return true;
			});
			// 4.5 告知所有人群已经被解散, 这里要走groupMemberDao查询，缓存中可能没有屏蔽群的用户
			// #202: post-commit 清理串行无隔离曾导致任一步（Redis/infra 抖动）抛错中断后续所有
			// eviction、留下 TTL ~1 天的幽灵 key（解散群 getConfig 仍 200）。现每步独立 runQuietly，
			// 单步失败只记 warn，不得阻断后续步骤。
			runQuietly("deleteRoomCache", roomId, () -> roomCache.delete(roomId));
			runQuietly("evictMemberList", roomId, () -> groupMemberCache.evictMemberList(room.getId()));
			runQuietly("evictExceptMemberList", roomId, () -> groupMemberCache.evictExceptMemberList(room.getId()));
			runQuietly("evictAllMemberDetails", roomId, groupMemberCache::evictAllMemberDetails);
			// 新版解散群聊：群成员集合 key 整体删除；各成员的 userGroupsKey 只 sRem 本房间——
			// 成员（含群主）可能还有大群 DEF_ROOM 等其它成员资格，整 key del 会误清（#202）
			runQuietly("deleteGroupMembersKey", roomId, () -> cachePlusOps.del(gKey));
			runQuietly("clearMemberUserGroupsKeys", roomId, () -> memberUidList.forEach(memberUid ->
					cachePlusOps.sRem(PresenceCacheKeyBuilder.userGroupsKey(memberUid), room.getId())));
			runQuietly("syncOnline", roomId, () -> presenceSyncHelper.syncOnline(memberUidList, room.getId(), false));
			runQuietly("pushDissolution", roomId, () -> pushService.sendPushMsg(RoomAdapter.buildGroupDissolution(roomGroup.getRoomId()), memberUidList, uid));
			// #182/#153: 解散群时同样清 aiclaw 入群待批准去重标记，避免旧标记压制后续再次邀请通知。
			// 该调用在事务外，且只操作 Redis，失败不得阻断已发出的解散广播。
			runQuietly("onMembersRemoved", roomId, () -> aiclawParticipant.onMembersRemoved(roomId, memberUidList));
		} else {
			// 如果房间人员小于3人 那么直接解散群聊
			if (cachePlusOps.sCard(gKey) <= 3) {
				MemberExitReq exitReq = new MemberExitReq();
				exitReq.setRoomId(request.getRoomId());
				exitReq.setAccount(roomGroup.getAccount());
				exitGroupInternal(true, uid, exitReq);
				return;
			}

			if (transactionTemplate.execute(e -> {
				// 4.6 删除会话
				Boolean isDelContact = contactDao.removeByRoomId(roomId, Collections.singletonList(uid));
				AssertUtil.isTrue(isDelContact, "会话移除异常");
				// 4.7 删除群成员
				Boolean isDelGroupMember = groupMemberDao.removeByGroupId(roomGroup.getId(), Collections.singletonList(uid));
				AssertUtil.isTrue(isDelGroupMember, "群成员移除失败");
				return true;
			})) {
				// 新版退出群聊
				CacheKey uKey = PresenceCacheKeyBuilder.userGroupsKey(uid);

				cachePlusOps.sRem(gKey, uid);
				cachePlusOps.sRem(uKey, room.getId());
				presenceSyncHelper.syncOnline(Arrays.asList(uid), room.getId(), false);

				// 4.8 发送移除事件告知群成员
				groupMemberCache.evictMemberList(room.getId());
				groupMemberCache.evictExceptMemberList(room.getId());
				groupMemberCache.evictMemberDetail(room.getId(), uid);
				WsBaseResp<WSMemberChange> ws = MemberAdapter.buildMemberRemoveWS(roomGroup.getRoomId(), Math.toIntExact(cachePlusOps.sCard(gKey)), Math.toIntExact(cachePlusOps.sCard(PresenceCacheKeyBuilder.onlineGroupMembersKey(room.getId()))), Arrays.asList(uid), WSMemberChange.CHANGE_TYPE_QUIT);
				pushService.sendPushMsg(ws, memberUidList, uid);

				// #153 P1-2: 退群者若是 aiclaw → 清入群待批准去重标记（接缝内按 aiclaw 判定）。
				// aiclaw 主动退群后若再被拉回，应重新给主人发待批准通知，不能被旧标记压制 24h。
				aiclawParticipant.onMembersRemoved(roomId, Collections.singletonList(uid));
			}
		}
	}

	@RedissonLock(prefixKey = "addGroup:", key = "#uid")
	public Long addGroup(Long uid, GroupAddReq request) {
		Map<Long, SummeryInfoDTO> inviteUserMap = userSummaryCache.getBatch(request.getUidList());
		inviteUserMap.remove(DefValConstants.DEF_BOT_ID);
		AssertUtil.isTrue(inviteUserMap.size() > 1, "群聊人数应大于2人");

		List<Long> inviteUidList = new ArrayList<>(inviteUserMap.keySet());
		AtomicReference<Long> roomIdAtomic = new AtomicReference(0L);

		// 创建群组数据并推送数据到前端
		if (transactionTemplate.execute(e -> {
			RoomGroup roomGroup = roomService.createGroupRoom(uid, request);
			// 批量保存群成员
			List<GroupMember> groupMembers = RoomAdapter.buildGroupMemberBatch(inviteUidList, roomGroup.getId());
			groupMemberDao.saveBatch(groupMembers);

			// 添加所有人的会话
			inviteUidList.add(uid);
			for (Long memberId : inviteUidList) {
				contactDao.refreshOrCreate(roomGroup.getRoomId(), memberId);
			}

			roomIdAtomic.set(roomGroup.getRoomId());
			return true;
		})) {
			// 发送邀请加群消息 ==> 触发每个人的会话
			roomGroupCache.evictAllCaches();
			// 处理新房间里面所有在线人员
			groupMemberCache.evictMemberList(roomIdAtomic.get());
			groupMemberCache.evictExceptMemberList(roomIdAtomic.get());

			// 更新在线缓存
			CacheKey onlineGroupMembersKey = PresenceCacheKeyBuilder.onlineGroupMembersKey(roomIdAtomic.get());
			CacheKey gKey = PresenceCacheKeyBuilder.groupMembersKey(roomIdAtomic.get());
			inviteUidList.forEach(id -> {
				cachePlusOps.sAdd(gKey, id);
				cachePlusOps.sAdd(PresenceCacheKeyBuilder.userGroupsKey(id), roomIdAtomic.get());
			});
			presenceSyncHelper.syncOnline(inviteUidList, roomIdAtomic.get(), true);
			SpringUtils.publishEvent(new GroupMemberAddEvent(this, roomIdAtomic.get(), Math.toIntExact(cachePlusOps.sCard(gKey)), Math.toIntExact(cachePlusOps.sCard(onlineGroupMembersKey)), request.getUidList(), uid));
		}
		return roomIdAtomic.get();
	}

	/**
	 * #202: post-commit 缓存清理步骤隔离执行——任一步（Redis/infra 抖动）抛错只记 warn，
	 * 不得中断后续 eviction 步骤，避免留下 TTL ~1 天的幽灵 key。
	 */
	private void runQuietly(String step, Long roomId, Runnable r) {
		try {
			r.run();
		} catch (Exception e) {
			log.warn("解散群 post-commit 清理步骤失败，吞异常继续: roomId={}, step={}", roomId, step, e);
		}
	}

	/**
	 * 获取群角色
	 */
	private Integer getGroupRole(Long uid, Long groupId) {
		GroupMember member = Objects.isNull(uid) ? null : groupMemberDao.getMemberByGroupId(groupId, uid);
		if (Objects.nonNull(member)) {
			return GroupRoleAPPEnum.of(member.getRoleId()).getType();
		} else {
			return GroupRoleAPPEnum.REMOVE.getType();
		}
	}

	@Transactional(rollbackFor = Exception.class)
	public void disbandGroup(DisbandGroupReq request) {
		RoomGroup roomGroup = roomGroupCache.getByRoomIdFromDb(request.getRoomId());
		AssertUtil.isNotEmpty(roomGroup, GroupErrorEnum.GROUP_NOT_EXIST);

		// 获取群主uid
		GroupMember lord = groupMemberDao.lambdaQuery()
				.eq(GroupMember::getGroupId, roomGroup.getId())
				.eq(GroupMember::getRoleId, GroupRoleEnum.LEADER.getType())
				.one();
		AssertUtil.isNotEmpty(lord, "群主不存在");

		// 复用exitGroup方法解散群聊（走 core，保持拆分前 disbandGroup 自调用绕过 exitGroup 锁的语义）
		MemberExitReq exitReq = new MemberExitReq();
		exitReq.setRoomId(request.getRoomId());
		exitGroupInternal(true, lord.getUid(), exitReq);
	}
}
