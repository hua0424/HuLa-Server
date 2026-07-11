package com.luohuo.flex.im.core.chat.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.model.cache.CacheKey;
import com.luohuo.flex.common.OnlineService;
import com.luohuo.flex.common.cache.PresenceCacheKeyBuilder;
import com.luohuo.flex.common.constant.DefValConstants;
import com.luohuo.flex.im.core.chat.dao.RoomGroupDao;
import com.luohuo.flex.im.core.user.dao.NoticeDao;
import com.luohuo.flex.im.core.user.dao.UserApplyDao;
import com.luohuo.flex.im.core.user.service.NoticeService;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.domain.dto.SummeryInfoDTO;
import com.luohuo.flex.im.domain.entity.*;
import com.luohuo.flex.im.domain.enums.*;
import com.luohuo.flex.im.domain.vo.req.room.GroupMemberPageReq;
import com.luohuo.flex.im.domain.vo.req.room.UpdateMemberNicknameReq;
import com.luohuo.flex.im.domain.vo.request.admin.AdminSetReq;
import com.luohuo.flex.im.domain.vo.request.member.MemberExitReq;
import com.luohuo.flex.im.domain.vo.res.PageBaseResp;
import com.luohuo.flex.im.domain.vo.resp.room.AiclawMemberResp;
import com.luohuo.flex.im.domain.vo.resp.room.GroupMemberSimpleResp;
import com.luohuo.flex.model.entity.ws.AdminChangeDTO;
import com.luohuo.flex.model.enums.ChatActiveStatusEnum;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import com.luohuo.basic.exception.BizException;
import com.luohuo.basic.exception.code.GroupErrorEnum;
import com.luohuo.basic.validator.utils.AssertUtil;
import com.luohuo.flex.model.redis.annotation.RedissonLock;
import com.luohuo.flex.im.core.chat.dao.ContactDao;
import com.luohuo.flex.im.core.chat.dao.GroupMemberDao;
import com.luohuo.flex.im.domain.vo.request.ChatMessageMemberReq;
import com.luohuo.flex.im.domain.vo.request.member.MemberAddReq;
import com.luohuo.flex.im.domain.vo.request.member.MemberDelReq;
import com.luohuo.flex.im.domain.vo.request.member.MemberReq;
import com.luohuo.flex.im.domain.vo.response.ChatMemberListResp;
import com.luohuo.flex.im.domain.vo.response.MemberResp;
import com.luohuo.flex.im.core.chat.service.AiclawParticipant;
import com.luohuo.flex.im.core.chat.service.adapter.MemberAdapter;
import com.luohuo.flex.im.core.chat.service.adapter.MessageAdapter;
import com.luohuo.flex.im.core.chat.service.adapter.RoomAdapter;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomGroupCache;
import com.luohuo.flex.im.core.user.dao.UserDao;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.ChatMemberResp;
import com.luohuo.flex.model.entity.ws.WSMemberChange;
import com.luohuo.flex.im.core.user.service.RoleService;
import com.luohuo.flex.im.core.user.service.cache.UserCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;

import java.util.*;
import java.util.stream.Collectors;

import static com.luohuo.flex.im.core.chat.constant.GroupConst.MAX_MANAGE_COUNT;
import static com.luohuo.flex.im.domain.enums.ApplyReadStatusEnum.UNREAD;

/**
 * 群成员管理器（从 {@link RoomAppServiceImpl} 拆出，#170）。
 * <p>负责：加/删成员、管理员增撤、群昵称维护、成员列表查询、aiclaw 成员查询。</p>
 *
 * <p><b>AOP 代理注意</b>：本类的 {@code delMember}（携带 {@link RedissonLock}）历史上<b>自调用</b>
 * {@code exitGroup}，因自调用而绕过 exitGroup 的锁。拆分后 exitGroup 归入
 * {@link GroupLifecycleManager}，为保持「不新增锁」的行为，delMember 调用其<b>无注解</b>的
 * {@link GroupLifecycleManager#exitGroupInternal} core，而非公开的 @RedissonLock 包装方法。</p>
 */
@Slf4j
@Service
@AllArgsConstructor
public class GroupMembershipManager {

	private final RoomGroupDao roomGroupDao;
	private final NoticeDao noticeDao;
	private final UserApplyDao userApplyDao;
	private final AiclawParticipant aiclawParticipant;
	private ContactDao contactDao;
	private RoomCache roomCache;
	private RoomGroupCache roomGroupCache;
	private CachePlusOps cachePlusOps;
	private UserCache userCache;
	private UserSummaryCache userSummaryCache;
	private GroupMemberDao groupMemberDao;
	private UserDao userDao;
	private RoleService roleService;
	private UidGenerator uidGenerator;
	private NoticeService noticeService;
	private GroupMemberCache groupMemberCache;
	private PushService pushService;
	private OnlineService onlineService;
	private TransactionTemplate transactionTemplate;
	private final GroupLifecycleManager groupLifecycleManager;
	private final PresenceSyncHelper presenceSyncHelper;

	/** 暖群成员缓存；供 shell.afterPropertiesSet 遍历调用（无注解，无代理语义）。 */
	public void warmUpGroupMemberCache(Long roomId) {
		// 1. 查询房间中所有用户
		List<Long> memberUidList = groupMemberCache.getMemberUidList(roomId);
		// 2. 更新 群里与用户的关系
		CacheKey cacheKey = PresenceCacheKeyBuilder.groupMembersKey(roomId);
		memberUidList.forEach(memberId -> cachePlusOps.sAdd(cacheKey, memberId));
	}

	/**
	 * 增加管理员
	 */
	@RedissonLock(prefixKey = "addAdmin:", key = "#request.roomId")
	@Transactional(rollbackFor = Exception.class)
	public void addAdmin(Long uid, AdminSetReq request) {
		// 1. 判断群聊是否存在
		RoomGroup roomGroup = verifyGet(uid, request);

		// 2. 判断管理员数量是否达到上限
		// 2.1 查询现有管理员数量
		List<Long> manageUidList = groupMemberDao.getManageUidList(roomGroup.getId());
		// 2.2 去重
		HashSet<Long> manageUidSet = new HashSet<>(manageUidList);
		manageUidSet.addAll(request.getUidList());
		AssertUtil.isFalse(manageUidSet.size() > MAX_MANAGE_COUNT, GroupErrorEnum.MANAGE_COUNT_EXCEED);

		// 3. 增加管理员
		groupMemberDao.addAdmin(roomGroup.getId(), request.getUidList());

		// 5. 发送给所有群成员
		List<Long> memberUidList = groupMemberCache.getMemberUidList(roomGroup.getRoomId());
		pushService.sendPushMsg(MessageAdapter.buildSetAdminMessage(new AdminChangeDTO(roomGroup.getRoomId(), request.getUidList(), true)), memberUidList, uid);

		// 每个被邀请的人都要收到邀请进群的消息
		setAdminNotice(NoticeTypeEnum.GROUP_SET_ADMIN, uid, request.getUidList(), manageUidList, roomGroup.getRoomId());
	}

	private void setAdminNotice(NoticeTypeEnum noticeTypeEnum, Long uid, List<Long> uidList, List<Long> manageUidList, Long roomId) {
		long uuid = uidGenerator.getUid();
		uidList.stream().filter(id -> !manageUidList.contains(id)).forEach(id -> {
			// 通知被操作的人
			noticeService.createNotice(
					RoomTypeEnum.GROUP,
					noticeTypeEnum,
					uid,
					id,
					uuid,
					id,
					roomId,
					""
			);

			// 通知群主
//			noticeService.createNotice(
//					RoomTypeEnum.GROUP,
//					noticeTypeEnum,
//					uid,
//					uid,
//					uuid,
//					id,
//					roomId,
//					""
//			);
		});
	}

	/**
	 * 撤销管理员
	 */
	@RedissonLock(prefixKey = "revokeAdmin:", key = "#request.roomId")
	@Transactional(rollbackFor = Exception.class)
	public void revokeAdmin(Long uid, AdminSetReq request) {
		// 1. 校验
		RoomGroup roomGroup = verifyGet(uid, request);

		// 2. 撤销管理员
		groupMemberDao.revokeAdmin(roomGroup.getId(), request.getUidList());
		List<Long> memberUidList = groupMemberCache.getMemberUidList(roomGroup.getRoomId());
		pushService.sendPushMsg(MessageAdapter.buildSetAdminMessage(new AdminChangeDTO(roomGroup.getRoomId(), request.getUidList(), false)), memberUidList, uid);

		setAdminNotice(NoticeTypeEnum.GROUP_RECALL_ADMIN, uid, request.getUidList(), new ArrayList<>(), roomGroup.getRoomId());
	}

	/**
	 * 校验人员在群里的权限
	 */
	private RoomGroup verifyGet(Long uid, AdminSetReq request) {
		// 1. 判断群聊是否存在
		RoomGroup roomGroup = roomGroupCache.getByRoomIdFromDb(request.getRoomId());
		AssertUtil.isNotEmpty(roomGroup, GroupErrorEnum.GROUP_NOT_EXIST);

		// 2. 判断该用户是否是群主
		Boolean isLord = groupMemberDao.isLord(roomGroup.getId(), uid);
		AssertUtil.isTrue(isLord, GroupErrorEnum.NOT_ALLOWED_OPERATION);

		// 3. 判断群成员是否在群中
		Boolean isGroupShip = groupMemberDao.isGroupShip(roomGroup.getRoomId(), request.getUidList());
		AssertUtil.isTrue(isGroupShip, GroupErrorEnum.USER_NOT_IN_GROUP);
		return roomGroup;
	}

	public List<ChatMemberResp> listMember(MemberReq request) {
		// 1. 基础校验
		Room room = roomCache.get(request.getRoomId());
		AssertUtil.isNotEmpty(room, "房间号有误");
		if (RoomTypeEnum.FRIEND.getType().equals(room.getType())) {
			throw new BizException("当前房间非群聊");
		}

		// 2. 获取群组和成员数据
		List<ChatMemberResp> chatMemberResps = groupMemberDao.getMemberListByGroupId(roomGroupCache.get(request.getRoomId()).getId());

		// 3. 批量获取用户信息
		List<Long> uids = chatMemberResps.stream().map(ChatMemberResp::getUid).map(Long::parseLong).collect(Collectors.toList());
		Map<Long, SummeryInfoDTO> batch = userSummaryCache.getBatch(uids);

		// 5. 批量获取在线状态
		Set<Long> onlineList = onlineService.getOnlineUsersList(new ArrayList<>(uids));

		// 6. 填充用户信息和在线状态
		chatMemberResps.forEach(item -> {
			Long uid = Long.parseLong(item.getUid());
			SummeryInfoDTO user = batch.get(uid);

			if (user != null) {
				item.setActiveStatus(onlineList.contains(uid) ? ChatActiveStatusEnum.ONLINE.getStatus() : ChatActiveStatusEnum.OFFLINE.getStatus());
				item.setLastOptTime(user.getLastOptTime());
				item.setName(user.getName());
				item.setAvatar(user.getAvatar());
				item.setLocPlace(user.getLocPlace());
				item.setAccount(user.getAccount());
				item.setUserStateId(user.getUserStateId());
				item.setItemIds(user.getItemIds());
				item.setUserType(user.getUserType());
				item.setWearingItemId(user.getWearingItemId());
				item.setLinkedGitee(user.getLinkedGitee());
				item.setLinkedGithub(user.getLinkedGithub());
				item.setLinkedGitcode(user.getLinkedGitcode());
			}
		});

		// 7. 群主、管理员永远在前面
		return chatMemberResps;
	}

	public List<AiclawMemberResp> aiclawListMembers(Long roomId, boolean online, Long aiclawUid) {
		// 1. 服务层硬鉴权：房间须存在且为群聊（ADR-0002）
		Room room = roomCache.get(roomId);
		if (room == null || !room.isRoomGroup()) {
			throw new BizException("当前不在群聊中");
		}
		// 2. aiclaw 须为该群成员，否则不得查询成员
		if (groupMemberDao.getMember(roomId, aiclawUid) == null) {
			throw new BizException("未加入该群聊，无法查询成员");
		}

		// 3. 复用既有成员查询（已填充 activeStatus 在线状态 + name/account/roleId）
		List<ChatMemberResp> members = listMember(MemberReq.builder().roomId(roomId).build());

		// 4. 映射为精简响应；online=true 时仅保留在线成员
		Integer onlineStatus = ChatActiveStatusEnum.ONLINE.getStatus();
		return members.stream()
				.filter(m -> !online || onlineStatus.equals(m.getActiveStatus()))
				.map(m -> AiclawMemberResp.builder()
						.uid(m.getUid())
						.name(m.getName())
						.account(m.getAccount())
						.online(onlineStatus.equals(m.getActiveStatus()))
						.roleId(m.getRoleId())
						.build())
				.collect(Collectors.toList());
	}

	public List<ChatMemberListResp> getMemberList(ChatMessageMemberReq request) {
		Room room = roomCache.get(request.getRoomId());
		AssertUtil.isNotEmpty(room, "房间号有误");
		if (isHotGroup(room)) {
			// 全员群展示所有用户100名
			List<User> memberList = userDao.getMemberList();
			return MemberAdapter.buildMemberList(memberList);
		} else {
			RoomGroup roomGroup = roomGroupCache.get(room.getId());
			List<Long> memberUidList = groupMemberDao.getMemberUidList(roomGroup.getId(), null);
			Map<Long, User> batch = userCache.getBatch(memberUidList);
			return MemberAdapter.buildMemberList(batch);
		}
	}

	@RedissonLock(prefixKey = "delMember:", key = "#request.roomId")
	public void delMember(Long uid, MemberDelReq request) {
		Room room = roomCache.get(request.getRoomId());
		AssertUtil.isNotEmpty(room, "房间号有误");
		AssertUtil.isFalse(DefValConstants.DEF_ROOM_ID.equals(request.getRoomId()), "官方群聊无法移除");
		RoomGroup roomGroup = roomGroupCache.get(request.getRoomId());
		AssertUtil.isNotEmpty(roomGroup, "房间号有误");
		GroupMember self = groupMemberDao.getMemberByGroupId(roomGroup.getId(), uid);
		AssertUtil.isNotEmpty(self, GroupErrorEnum.USER_NOT_IN_GROUP, "");

		// 如果房间人员小于3人 那么直接解散群聊
		CacheKey membersKey = PresenceCacheKeyBuilder.groupMembersKey(request.getRoomId());
		Long memberNum = cachePlusOps.sCard(membersKey);
		if (memberNum <= 3) {
			MemberExitReq exitReq = new MemberExitReq();
			exitReq.setRoomId(request.getRoomId());
			exitReq.setAccount(roomGroup.getAccount());
			// #170 AOP: 拆分前 delMember 自调用 exitGroup 因自调用绕过其 @RedissonLock（不加锁）。
			// 跨 bean 调用 core（无注解）以保持「不新增锁」的语义。
			groupLifecycleManager.exitGroupInternal(true, uid, exitReq);
			return;
		}

		// 1. 判断被移除的人是否是群主或者管理员  （群主不可以被移除，管理员只能被群主移除）
		request.getUidList().forEach(removedUid -> {
			// 1.1 群主 非法操作
			AssertUtil.isFalse(groupMemberDao.isLord(roomGroup.getId(), removedUid), GroupErrorEnum.NOT_ALLOWED_FOR_REMOVE, "");
			// 1.2 管理员 判断是否是群主操作
			if (groupMemberDao.isManager(roomGroup.getId(), removedUid)) {
				Boolean isLord = groupMemberDao.isLord(roomGroup.getId(), uid);
				AssertUtil.isTrue(isLord, GroupErrorEnum.NOT_ALLOWED_FOR_REMOVE);
			}
			// 1.3 普通成员 判断是否有权限操作
			AssertUtil.isTrue(hasPower(self), GroupErrorEnum.NOT_ALLOWED_FOR_REMOVE);
			GroupMember member = groupMemberDao.getMemberByGroupId(roomGroup.getId(), removedUid);
			AssertUtil.isNotEmpty(member, "用户已经移除");

			// 发送移除事件告知群成员
			if (transactionTemplate.execute(e -> {
				groupMemberDao.removeById(member.getId());
				// 1.5 移除会话
				contactDao.removeByRoomId(room.getId(), Collections.singletonList(removedUid));
				return true;
			})) {
				// 移除群聊缓存
				CacheKey uKey = PresenceCacheKeyBuilder.userGroupsKey(removedUid);
				cachePlusOps.sRem(membersKey, removedUid);
				cachePlusOps.sRem(uKey, room.getId());
				presenceSyncHelper.syncOnline(Arrays.asList(removedUid), room.getId(), false);

				// 推送状态到前端
				List<Long> memberUidList = groupMemberCache.getMemberExceptUidList(roomGroup.getRoomId());
				if (!memberUidList.contains(removedUid)) {
					memberUidList.add(removedUid);
				}
				WsBaseResp<WSMemberChange> ws = MemberAdapter.buildMemberRemoveWS(roomGroup.getRoomId(), (int) (memberNum - 1), Math.toIntExact(cachePlusOps.sCard(PresenceCacheKeyBuilder.onlineGroupMembersKey(room.getId()))), Arrays.asList(member.getUid()), WSMemberChange.CHANGE_TYPE_REMOVE);
				pushService.sendPushMsg(ws, memberUidList, uid);
				groupMemberCache.evictMemberList(room.getId());
				groupMemberCache.evictExceptMemberList(room.getId());
				groupMemberCache.evictMemberDetail(room.getId(), removedUid);

				long uuid = uidGenerator.getUid();
				// 保存被删除人的通知
				noticeService.createNotice(
						RoomTypeEnum.GROUP,
						NoticeTypeEnum.GROUP_MEMBER_DELETE,
						uid,
						removedUid,
						uuid,
						removedUid,
						roomGroup.getRoomId(),
						roomGroup.getName()
				);

				// 获取所有管理员
				List<Long> managerIds = groupMemberDao.getGroupUsers(roomGroup.getId(),true);
				managerIds.forEach(managerId -> noticeService.createNotice(
						RoomTypeEnum.GROUP,
						NoticeTypeEnum.GROUP_MEMBER_DELETE,
						uid,
						managerId,
						uuid,
						removedUid,
						roomGroup.getRoomId(),
						roomGroup.getName()
				));

				// #153 P1-1: 被踢者若是 aiclaw → 清入群待批准去重标记（接缝内按 aiclaw 判定）。
				// 踢出 = 明确不想要这个 aiclaw，若之后再被拉回应重新给主人发待批准通知，不能被旧标记压制 24h。
				aiclawParticipant.onMembersRemoved(roomGroup.getRoomId(), Collections.singletonList(removedUid));
			}
		});
	}

	@RedissonLock(key = "#request.roomId")
	public void addMember(Long uid, MemberAddReq request) {
		HashSet<Long> inviteUidList = request.getUidList();
		AssertUtil.isNotEmpty(inviteUidList.contains(DefValConstants.DEF_BOT_ID), "不能拉小管家进群!");
		// 1. 校验数据
		Room room = roomCache.get(request.getRoomId());
		AssertUtil.isNotEmpty(room, "房间号有误");
		RoomGroup roomGroup = roomGroupCache.get(request.getRoomId());
		AssertUtil.isNotEmpty(roomGroup, "房间号有误");
		GroupMember self = groupMemberDao.getMemberByGroupId(roomGroup.getId(), uid);
		AssertUtil.isNotEmpty(self, "您不是群成员");
		// 已经进群了的
		List<Long> memberBatch = groupMemberDao.getMemberBatch(roomGroup.getId(), inviteUidList).stream().map(GroupMember::getUid).toList();
		// 已经邀请过的数据
		List<Long> existingUsers = userApplyDao.getExistingUsers(request.getRoomId(), inviteUidList);
		inviteUidList.removeAll(memberBatch);
		inviteUidList.removeAll(existingUsers);

		List<Long> validUids = new ArrayList<>(inviteUidList);
		if (CollectionUtils.isEmpty(validUids)) {
			return;
		}

		// #157: 停用/注销用户（已停用 aiclaw 的 im_user.is_del=1）不被 listByIds 返回；旧逻辑放它们进普通
		// 邀请路径 → NoticeServiceImpl.convertToVO 查不到 summary → NPE→500。此处明确拦截给可读业务错误。
		List<User> resolvedUsers = userDao.listByIds(validUids);
		Set<Long> resolvedUids = resolvedUsers.stream().map(User::getId).collect(Collectors.toSet());
		List<Long> unresolvable = validUids.stream().filter(u -> !resolvedUids.contains(u)).collect(Collectors.toList());
		if (!unresolvable.isEmpty()) {
			throw new BizException("无法邀请：目标用户不存在或已注销/停用");
		}

		// REQ-009 #88: 被邀请人中的所有 aiclaw（userType=4）无论归属，统一自动入群（pending），
		// 并按需给主人发待批准通知（接缝内含 #153 去重+补偿回滚）。别人拉你的 aiclaw 也走自动入群，
		// 避免落入普通邀请流程而无 UI 可接受。返回已自动入群的 aiclaw，从普通邀请流程剔除。
		Set<Long> autoAgreeUids = aiclawParticipant.autoJoinInvitedAiclaws(roomGroup, resolvedUsers, uid);
		validUids.removeAll(autoAgreeUids);

		// 非 aiclaw：走原有邀请流程
		if (CollectionUtils.isEmpty(validUids)) {
			return;
		}

		// 2. 创建邀请记录
		List<UserApply> invites = validUids.stream().map(inviteeUid -> new UserApply(uid, RoomTypeEnum.GROUP.getType(), roomGroup.getRoomId(), inviteeUid, StrUtil.format("{}邀请你加入{}", userSummaryCache.get(uid).getName(), roomGroup.getName()), NoticeStatusEnum.UNTREATED.getStatus(), UNREAD.getCode(), 0, false, 1)).collect(Collectors.toList());
		transactionTemplate.execute(e -> userApplyDao.saveBatch(invites));

		// 3. 通知被邀请的人进群, 通知时绑定通知id
		List<Long> managerIds = groupMemberDao.getGroupUsers(roomGroup.getId(), true);
		invites.forEach(invite -> {
			SummeryInfoDTO user = userSummaryCache.get(invite.getTargetId());
			if (ObjectUtil.isNotNull(user)) {
				pushService.sendPushMsg(MessageAdapter.buildInviteeUserAddGroupMessage(noticeDao.getUnReadCount(invite.getTargetId(), invite.getTargetId())), invite.getTargetId(), uid);
			}

			// 每个被邀请的人都要收到邀请进群的消息
			noticeService.createNotice(
					RoomTypeEnum.GROUP,
					NoticeTypeEnum.GROUP_INVITE_ME,
					uid,
					invite.getTargetId(),
					invite.getId(),
					invite.getTargetId(),
					roomGroup.getRoomId(),
					roomGroup.getName()
			);

			// 每个管理员都要收到邀请进群的消息
			managerIds.forEach(managerId -> noticeService.createNotice(
					RoomTypeEnum.GROUP,
					NoticeTypeEnum.GROUP_INVITE,
					uid,
					managerId,
					invite.getId(),
					invite.getTargetId(),
					roomGroup.getRoomId(),
					roomGroup.getName()
			));
		});
	}

	private boolean hasPower(GroupMember self) {
		return Objects.equals(self.getRoleId(), GroupRoleEnum.LEADER.getType())
				|| Objects.equals(self.getRoleId(), GroupRoleEnum.MANAGER.getType())
				|| roleService.hasRole(self.getUid(), RoleTypeEnum.ADMIN);
	}

	private boolean isHotGroup(Room room) {
		return HotFlagEnum.YES.getType().equals(room.getHotFlag());
	}

	public PageBaseResp<GroupMemberSimpleResp> getGroupMemberPage(GroupMemberPageReq request) {
		// 获取群组信息
		RoomGroup roomGroup = roomGroupDao.getOne(Wrappers.<RoomGroup>lambdaQuery()
				.eq(RoomGroup::getRoomId, request.getRoomId()));
		AssertUtil.isNotEmpty(roomGroup, GroupErrorEnum.GROUP_NOT_EXIST);

		// 分页查询群成员
		Page<GroupMember> page = new Page<>(request.getPageNo(), request.getPageSize());

		IPage<GroupMember> memberPage = groupMemberDao.page(page,
				Wrappers.<GroupMember>lambdaQuery()
						.eq(GroupMember::getGroupId, roomGroup.getId())
						.orderByAsc(GroupMember::getRoleId)
						.orderByDesc(GroupMember::getCreateTime));

		// 获取用户ID列表
		List<Long> uidList = memberPage.getRecords().stream()
				.map(GroupMember::getUid)
				.collect(Collectors.toList());

		if (CollUtil.isEmpty(uidList)) {
			return PageBaseResp.empty();
		}

		// 批量获取用户信息
		List<User> users = userDao.listByIds(uidList);
		Map<Long, User> userMap = users.stream()
				.collect(Collectors.toMap(User::getId, java.util.function.Function.identity()));

		// 批量获取在线状态
		Map<Long, Boolean> onlineStatusMap = onlineService.getUsersOnlineStatus(uidList);

		// 构建响应列表
		List<GroupMemberSimpleResp> list = memberPage.getRecords().stream()
				.map(member -> {
					User user = userMap.get(member.getUid());
					if (user == null) {
						return null;
					}

					Boolean isOnline = onlineStatusMap.getOrDefault(member.getUid(), false);
					Integer onlineStatus = isOnline ? ChatActiveStatusEnum.ONLINE.getStatus() : ChatActiveStatusEnum.OFFLINE.getStatus();

					String locPlace = "";
					String ipAddress = "";
					if (user.getIpInfo() != null && user.getIpInfo().getUpdateIpDetail() != null) {
						locPlace = user.getIpInfo().getUpdateIpDetail().getCity() != null ? user.getIpInfo().getUpdateIpDetail().getCity() : "";
						ipAddress = user.getIpInfo().getUpdateIp() != null ? user.getIpInfo().getUpdateIp() : "";
					}

					return GroupMemberSimpleResp.builder()
							.uid(String.valueOf(member.getUid()))
							.name(StrUtil.isEmpty(member.getMyName())? user.getName() : member.getMyName())
							.roleId(member.getRoleId())
							.userType(user.getUserType())
							.activeStatus(onlineStatus)
							.locPlace(locPlace)
							.ipAddress(ipAddress)
							.build();
				})
				.filter(Objects::nonNull)
				.collect(Collectors.toList());

		return PageBaseResp.init(
				(int) memberPage.getCurrent(),
				(int) memberPage.getSize(),
				memberPage.getTotal(),
				list);
	}

	@Transactional(rollbackFor = Exception.class)
	public void updateMemberNickname(UpdateMemberNicknameReq request) {
		// 1. 根据roomId和uid查询群成员
		GroupMember member = groupMemberDao.getMember(request.getRoomId(), request.getUid());
		AssertUtil.isNotEmpty(member, "群成员不存在");

		// 2. 修改昵称和备注
		boolean equals = member.getMyName().equals(StrUtil.isEmpty(request.getMyName()) ? "" : request.getMyName());
		boolean success = groupMemberDao.update(null, Wrappers.<GroupMember>lambdaUpdate()
				.set(GroupMember::getRemark, request.getRemark())
				.set(GroupMember::getMyName, request.getMyName())
				.eq(GroupMember::getId, member.getId()));

		// 3. 清除缓存
		groupMemberCache.evictMemberDetail(request.getRoomId(), request.getUid());

		// 4. 通知群里所有人昵称改变了
		if (!equals && success) {
			List<Long> memberUidList = groupMemberCache.getMemberExceptUidList(request.getRoomId());
			pushService.sendPushMsg(RoomAdapter.buildMyRoomGroupChangeWS(request.getRoomId(), request.getUid(), request.getMyName()), memberUidList, request.getUid());
		}
	}
}
