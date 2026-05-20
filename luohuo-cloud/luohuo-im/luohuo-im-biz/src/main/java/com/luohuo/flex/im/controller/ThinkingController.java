package com.luohuo.flex.im.controller;

import com.luohuo.basic.base.R;
import com.luohuo.flex.im.core.chat.mapper.GroupMemberMapper;
import com.luohuo.flex.im.core.chat.mapper.RoomGroupMapper;
import com.luohuo.flex.im.core.chat.service.ThinkingService;
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

	/**
	 * 创建 thinking 记录
	 */
	@PostMapping("/start")
	public R<Long> start(@RequestBody WSThinkingStart req) {
		Long aiclawUid = Long.valueOf(req.getFromUid());
		Long roomId = Long.valueOf(req.getRoomId());
		Long triggerMsgId = req.getTriggerMsgId() != null ? Long.valueOf(req.getTriggerMsgId()) : null;

		Long thinkingId = thinkingService.create(aiclawUid, roomId, triggerMsgId);
		return R.success(thinkingId);
	}

	/**
	 * 追加 delta 内容
	 */
	@PostMapping("/delta")
	public R<Void> delta(@RequestBody WSThinkingDelta req) {
		Long thinkingId = Long.valueOf(req.getThinkingId());
		thinkingService.appendDelta(thinkingId, req.getChunk(), req.getSeq());
		return R.success();
	}

	/**
	 * 结束 thinking 记录
	 */
	@PostMapping("/end")
	public R<Void> end(@RequestBody WSThinkingEnd req) {
		Long thinkingId = Long.valueOf(req.getThinkingId());
		thinkingService.finalize(thinkingId, req.getDurationMs());
		return R.success();
	}

	/**
	 * 查询群成员 UID 列表
	 */
	@GetMapping("/room/{roomId}/members")
	public R<List<Long>> getRoomMembers(@PathVariable Long roomId) {
		GroupResp group = roomGroupMapper.getByRoomIdIgnoreDel(roomId);
		if (group == null || group.getGroupId() == null) {
			return R.success(List.of());
		}
		List<Long> uids = groupMemberMapper.getMemberListByGroupId(group.getGroupId())
				.stream()
				.map(m -> Long.valueOf(m.getUid()))
				.collect(Collectors.toList());
		return R.success(uids);
	}
}
