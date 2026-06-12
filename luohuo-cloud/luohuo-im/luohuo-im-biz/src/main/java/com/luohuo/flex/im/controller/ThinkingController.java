package com.luohuo.flex.im.controller;

import com.luohuo.basic.base.R;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.mapper.GroupMemberMapper;
import com.luohuo.flex.im.core.chat.mapper.RoomGroupMapper;
import com.luohuo.flex.im.core.chat.service.ThinkingService;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import com.luohuo.flex.im.domain.vo.response.GroupResp;
import com.luohuo.flex.model.entity.ws.WSThinkingDelta;
import com.luohuo.flex.model.entity.ws.WSThinkingEnd;
import com.luohuo.flex.model.entity.ws.WSThinkingStart;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thinking 管理接口（供 ws-biz ThinkingProcessor 调用）
 */
@Slf4j
@RestController
@RequestMapping("/thinking")
public class ThinkingController {

	@Resource
	private ThinkingService thinkingService;
	@Resource
	private GroupMemberMapper groupMemberMapper;
	@Resource
	private RoomGroupMapper roomGroupMapper;
	@Resource
	private RoomFriendDao roomFriendDao;

	/**
	 * 创建 thinking 记录
	 */
	@PostMapping("/start")
	public R<Long> start(@RequestBody WSThinkingStart req) {
		ensureTenantId();
		Long aiclawUid = Long.valueOf(req.getFromUid());
		Long roomId = Long.valueOf(req.getRoomId());
		Long triggerMsgId = req.getTriggerMsgId() != null ? Long.valueOf(req.getTriggerMsgId()) : null;

		// aichatoverview#3: aiclaw 房间成员校验
		checkAiclawRoomMembership(aiclawUid, roomId);

		Long thinkingId = thinkingService.create(aiclawUid, roomId, triggerMsgId);
		return R.success(thinkingId);
	}

	/**
	 * 追加 delta 内容
	 */
	@PostMapping("/delta")
	public R<Void> delta(@RequestBody WSThinkingDelta req) {
		ensureTenantId();
		Long thinkingId = Long.valueOf(req.getThinkingId());
		thinkingService.appendDelta(thinkingId, req.getChunk(), req.getSeq());
		return R.success();
	}

	/**
	 * 结束 thinking 记录
	 */
	@PostMapping("/end")
	public R<Void> end(@RequestBody WSThinkingEnd req) {
		ensureTenantId();
		Long thinkingId = Long.valueOf(req.getThinkingId());
		thinkingService.finalize(thinkingId, req.getDurationMs());
		return R.success();
	}

	/**
	 * 标记 thinking 为错误状态（限流拒绝等场景）
	 */
	@PostMapping("/error")
	public R<Void> error(@RequestBody WSThinkingEnd req) {
		ensureTenantId();
		Long thinkingId = Long.valueOf(req.getThinkingId());
		thinkingService.markError(thinkingId, req.getError());
		return R.success();
	}

	/**
	 * aichatoverview#3: aiclaw 房间成员校验。
	 * 群聊检查 groupMemberMapper，私聊检查 RoomFriendDao。
	 * 非成员抛 BizException。
	 */
	private void checkAiclawRoomMembership(Long aiclawUid, Long roomId) {
		// 1. 先查群聊
		GroupResp group = roomGroupMapper.getByRoomIdIgnoreDel(roomId);
		if (group != null && group.getGroupId() != null) {
			List<Long> uids = groupMemberMapper.getMemberListByGroupId(group.getGroupId())
					.stream()
					.map(m -> Long.valueOf(m.getUid()))
					.collect(Collectors.toList());
			if (!uids.contains(aiclawUid)) {
				throw new BizException("非房间成员，无法创建 thinking");
			}
			return;
		}

		// 2. 私聊分支
		RoomFriend friend = roomFriendDao.getByRoomId(roomId);
		if (friend != null) {
			boolean isMember = aiclawUid.equals(friend.getUid1()) || aiclawUid.equals(friend.getUid2());
			if (!isMember) {
				throw new BizException("非房间成员，无法创建 thinking");
			}
			return;
		}

		// 3. 既不是群也不是私聊
		throw new BizException("房间不存在");
	}

	/**
	 * ws-server 通过 HTTP 调用时不携带租户上下文，MyBatis-Plus tenant interceptor
	 * 需要从 ContextUtil 读取 tenant_id。此处兜底设置默认租户编号。
	 */
	private void ensureTenantId() {
		if (com.luohuo.basic.context.ContextUtil.getTenantId() == null) {
			com.luohuo.basic.context.ContextUtil.setTenantId(1L);
		}
	}

	/**
	 * 查询群成员 UID 列表
	 */
	@GetMapping("/room/{roomId}/members")
	public R<List<Long>> getRoomMembers(@PathVariable Long roomId) {
		ensureTenantId();

		// 1. 先查群聊
		GroupResp group = roomGroupMapper.getByRoomIdIgnoreDel(roomId);
		if (group != null && group.getGroupId() != null) {
			List<Long> uids = groupMemberMapper.getMemberListByGroupId(group.getGroupId())
					.stream()
					.map(m -> Long.valueOf(m.getUid()))
					.collect(Collectors.toList());
			return R.success(uids);
		}

		// 2. 私聊分支
		RoomFriend friend = roomFriendDao.getByRoomId(roomId);
		if (friend != null) {
			return R.success(List.of(friend.getUid1(), friend.getUid2()));
		}

		return R.success(List.of());
	}
}
