package com.luohuo.flex.im.core.user.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.luohuo.flex.im.core.chat.dao.RoomGroupDao;
import com.luohuo.flex.im.core.user.dao.NoticeDao;
import com.luohuo.flex.im.core.user.service.NoticeService;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.domain.dto.SummeryInfoDTO;
import com.luohuo.flex.im.domain.entity.Notice;
import com.luohuo.flex.im.domain.entity.RoomGroup;
import com.luohuo.flex.im.domain.enums.NoticeStatusEnum;
import com.luohuo.flex.im.domain.enums.NoticeTypeEnum;
import com.luohuo.flex.im.domain.enums.RoomTypeEnum;
import com.luohuo.flex.im.domain.vo.req.NoticeReq;
import com.luohuo.flex.im.domain.vo.res.NoticeVO;
import com.luohuo.flex.im.domain.vo.res.PageBaseResp;
import com.luohuo.flex.model.entity.WSRespTypeEnum;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.WSNotice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.luohuo.flex.im.domain.enums.ApplyReadStatusEnum.UNREAD;

@Service
@RequiredArgsConstructor
public class NoticeServiceImpl implements NoticeService {
    
    private final NoticeDao noticeDao;
	private final PushService pushService;
	private final UserSummaryCache userSummaryCache;
	private final RoomGroupDao roomGroupDao;

	private void pushNoticeToUser(Long receiverId, Notice notice) {
		WsBaseResp<NoticeVO> wsMsg = new WsBaseResp<>();
		wsMsg.setData(convertToVO(notice));
		wsMsg.setType(WSRespTypeEnum.NOTIFY_EVENT.getType());
		pushService.sendPushMsg(wsMsg, Collections.singletonList(receiverId), notice.getSenderId());
	}

	@Override
	public Notice getByApplyId(Long uid, Long applyId) {
		return noticeDao.getBaseMapper().selectOne(new QueryWrapper<Notice>().eq("receiver_id", uid).eq("apply_id", applyId));
	}

	@Override
	public void createNotice(RoomTypeEnum applyType, NoticeTypeEnum type, Long senderId, Long receiverId, Long applyId, Long operate, String content) {
		createNotice(applyType, type, senderId, receiverId, applyId, operate, 0L, content);
	}

	@Override
	public void createNotice(RoomTypeEnum applyType, NoticeTypeEnum type, Long senderId, Long receiverId, Long applyId, Long operate, Long roomId, String content) {
		Notice notice = buildNotice(applyType, type, senderId, receiverId, applyId, operate, roomId, content);
		noticeDao.save(notice);

		// 实时推送
		pushNoticeToUser(receiverId, notice);
	}

	@Override
	public Notice buildNotice(RoomTypeEnum applyType, NoticeTypeEnum type, Long senderId, Long receiverId, Long applyId, Long operate, Long roomId, String content) {
		Notice notice = new Notice();
		notice.setType(applyType.getType());
		notice.setEventType(type.getType());
		notice.setSenderId(senderId);
		notice.setReceiverId(receiverId);
		notice.setApplyId(applyId);
		notice.setOperateId(operate);
		notice.setRoomId(roomId);
		notice.setContent(content);
		notice.setIsRead(UNREAD.getCode());
		notice.setStatus(NoticeStatusEnum.UNTREATED.getStatus());
		return notice;
	}

	@Override
	public void createNotices(List<Notice> notices) {
		// 一次批量 INSERT（回填每条 id），再逐条实时推送 —— 推送条数/内容/接收人与逐条 createNotice 一致。
		noticeDao.saveBatch(notices);
		notices.forEach(n -> pushNoticeToUser(n.getReceiverId(), n));
	}

	@Override
	public void updateNotice(Notice notice) {
		noticeDao.update(Wrappers.<Notice>lambdaUpdate().set(Notice::getStatus, notice.getStatus()).eq(Notice::getApplyId, notice.getApplyId()));

		// 实时推送
		pushNoticeToUser(notice.getReceiverId(), notice);
	}

	public void updateNotices(Notice notice) {
		noticeDao.update(Wrappers.<Notice>lambdaUpdate().set(Notice::getStatus, notice.getStatus()).eq(Notice::getApplyId, notice.getApplyId()));

		List<Notice> notices = noticeDao.getBaseMapper().selectList(new QueryWrapper<Notice>().eq("apply_id", notice.getApplyId()));
		for (Notice n : notices) {
			// 实时推送
			pushNoticeToUser(n.getReceiverId(), n);
		}
	}

	private void readNotices(Long uid, String applyType, IPage<Notice> noticeIPage) {
		List<Long> notices = noticeIPage.getRecords()
				.stream().map(Notice::getId)
				.collect(Collectors.toList());
		if(CollUtil.isNotEmpty(notices)){
			noticeDao.readNotices(uid, "friend".equals(applyType)? 2: 1, notices);
		}
	}

	@Override
	public PageBaseResp<NoticeVO> getUserNotices(Long uid, NoticeReq request) {
		IPage<Notice> noticeIPage = noticeDao.getUserNotices(uid, true, request.plusPage());
		// 将这些通知设为已读
		if(request.getClick()){
			readNotices(uid, request.getApplyType(), noticeIPage);
		}
		List<Notice> records = noticeIPage.getRecords();

		// #203: 页级批量回源，消除逐条 N+1 —— 整页一次群表查询 + 一次用户缓存批量查
		List<Long> roomIds = records.stream().map(Notice::getRoomId).filter(Objects::nonNull).distinct().collect(Collectors.toList());
		// 空 IN 守卫：全好友通知页/空页不查群表（对齐 #99）
		Map<Long, RoomGroup> groupMap = roomIds.isEmpty()
				? Collections.emptyMap()
				: roomGroupDao.listByRoomIds(roomIds).stream()
						.collect(Collectors.toMap(RoomGroup::getRoomId, Function.identity()));

		Set<Long> uids = new HashSet<>();
		records.forEach(n -> {
			if (n.getSenderId() != null) {
				uids.add(n.getSenderId());
			}
			if (n.getReceiverId() != null) {
				uids.add(n.getReceiverId());
			}
			if (n.getOperateId() != null) {
				uids.add(n.getOperateId());
			}
		});
		Map<Long, SummeryInfoDTO> summaryMap = uids.isEmpty()
				? Collections.emptyMap()
				: userSummaryCache.getBatch(new ArrayList<>(uids));

		return PageBaseResp.init(noticeIPage, records.stream().map(notice -> convertToVO(notice, summaryMap, groupMap)).collect(Collectors.toList()));
	}

	private NoticeVO convertToVO(Notice notice) {
		// 实时推送走单条路径：用户缓存等价批量查（get 内部本就委托 getBatch）；群信息仅列表响应内嵌，推送不带
		Set<Long> uids = new HashSet<>();
		if (notice.getSenderId() != null) {
			uids.add(notice.getSenderId());
		}
		if (notice.getReceiverId() != null) {
			uids.add(notice.getReceiverId());
		}
		if (notice.getOperateId() != null) {
			uids.add(notice.getOperateId());
		}
		Map<Long, SummeryInfoDTO> summaryMap = uids.isEmpty()
				? Collections.emptyMap()
				: userSummaryCache.getBatch(new ArrayList<>(uids));
		return convertToVO(notice, summaryMap, Collections.emptyMap());
	}

	private NoticeVO convertToVO(Notice notice, Map<Long, SummeryInfoDTO> summaryMap, Map<Long, RoomGroup> groupMap) {
		NoticeVO vo = new NoticeVO();
		vo.setId(notice.getId());
		vo.setApplyId(notice.getApplyId());
		vo.setSenderId(notice.getSenderId());
		vo.setReceiverId(notice.getReceiverId());
		vo.setOperateId(notice.getOperateId());
		vo.setRoomId(notice.getRoomId());
		vo.setContent(notice.getContent());
		vo.setEventType(notice.getEventType());
		vo.setType(notice.getType());
		vo.setStatus(notice.getStatus());
		vo.setCreateTime(notice.getCreateTime());
		vo.setRead(notice.getIsRead());

		// 填充发送人信息（#157: sender 可能为已删除/停用用户 → null，需空安全）
		SummeryInfoDTO sender = summaryMap.get(notice.getSenderId());
		vo.setSenderName(sender != null ? sender.getName() : null);
		vo.setSenderAvatar(sender != null ? sender.getAvatar() : null);

		// 填充接收人信息（#157: receiver 可能为已删除/停用用户 → null，需空安全）
		SummeryInfoDTO receiver = summaryMap.get(notice.getReceiverId());
		vo.setReceiverName(receiver != null ? receiver.getName() : null);
		vo.setReceiverAvatar(receiver != null ? receiver.getAvatar() : null);

		// receiverUserType 取实际被申请目标（operateId）的 userType
		// aiclaw 场景：operateId=aiclaw uid（userType=4），receiverId=owner uid（已转发）
		Long targetUid = notice.getOperateId() != null ? notice.getOperateId() : notice.getReceiverId();
		SummeryInfoDTO targetUser = targetUid.equals(notice.getReceiverId())
				? receiver
				: summaryMap.get(targetUid);
		Integer fallbackType = receiver != null ? receiver.getUserType() : null;
		vo.setReceiverUserType(targetUser != null ? targetUser.getUserType() : fallbackType);

		// #203: 群通知行内嵌群信息；群已解散时 listByRoomIds 查不到 → Map miss → 字段保持 null（契约）
		if (notice.getRoomId() != null) {
			RoomGroup group = groupMap.get(notice.getRoomId());
			if (group != null) {
				vo.setGroupName(group.getName());
				vo.setGroupAvatar(group.getAvatar());
			}
		}
		return vo;
	}

	@Override
	public void markAsRead(Long noticeId) {
		noticeDao.markAsRead(noticeId);
	}

	@Override
	public WSNotice unread(Long uid) {
		return noticeDao.getUnReadCount(uid, uid);
	}
}