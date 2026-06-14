package com.luohuo.flex.im.core.chat.service.adapter;

import cn.hutool.core.bean.BeanUtil;
import com.luohuo.basic.utils.TimeUtils;
import com.luohuo.flex.im.common.enums.YesOrNoEnum;
import com.luohuo.flex.im.domain.entity.Announcements;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.entity.MessageMark;
import com.luohuo.flex.im.domain.vo.response.msg.AudioCallMsgDTO;
import com.luohuo.flex.im.domain.vo.response.msg.BodyDTO;
import com.luohuo.flex.im.domain.vo.response.msg.VideoCallMsgDTO;
import com.luohuo.flex.im.domain.vo.response.msg.MergeMsgDTO;
import com.luohuo.flex.im.domain.vo.response.msg.NoticeMsgDTO;
import com.luohuo.flex.model.entity.ws.AdminChangeDTO;
import com.luohuo.flex.model.entity.ws.WSNotice;
import com.luohuo.flex.model.enums.MessageMarkTypeEnum;
import com.luohuo.flex.im.domain.enums.MessageStatusEnum;
import com.luohuo.flex.im.domain.enums.MessageTypeEnum;
import com.luohuo.flex.im.domain.vo.request.ChatMessageReq;
import com.luohuo.flex.im.domain.entity.msg.TextMsgReq;
import com.luohuo.flex.model.entity.ws.ChatMessageResp;
import com.luohuo.flex.im.domain.vo.response.ReadAnnouncementsResp;
import com.luohuo.flex.im.core.chat.service.strategy.msg.AbstractMsgHandler;
import com.luohuo.flex.im.core.chat.service.strategy.msg.MsgHandlerFactory;
import com.luohuo.flex.model.entity.WSRespTypeEnum;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.ws.CallEndReq;

import java.util.*;
import java.util.stream.Collectors;
import java.time.LocalDateTime;

public class MessageAdapter {
    public static final int CAN_CALLBACK_GAP_COUNT = 100;

    /**
     * ISS-015: 流式消息可携带 sendTime 覆盖默认 create_time 的最大回溯窗口(分钟)。
     * 超出此窗口的 sendTime 会被 clamp 到 [now-MAX_SEND_TIME_BACKDATE_MINUTES min, now]。
     * 防止恶意客户端把消息「插入」到很久以前的历史位置。
     */
    static final long MAX_SEND_TIME_BACKDATE_MINUTES = 5;

    public static Message buildMsgSave(ChatMessageReq request, Long uid) {
		Message msg = Message.builder()
                .fromUid(uid)
                .roomId(request.getRoomId())
                .type(request.getMsgType())
                .status(MessageStatusEnum.NORMAL.getStatus())
                .build();
		// ISS-015: AI 流式消息 stream_end 落库时,把 stream_start 时间戳回灌到 create_time。
		// 默认 MetaObjectHandler 会用 now() 填(=stream_end),导致长流式回复被新用户消息夹塞;
		// 这里在 skipPush=true(stream 路径独有标记)时,带 sendTime 就覆盖,并被 clamp 防注入。
		if (request.isSkipPush() && request.getSendTime() != null) {
			msg.setCreateTime(clampSendTime(request.getSendTime()));
		}
		return msg;
    }

    /**
     * ISS-015: 把客户端声明的 sendTime 钳制到 [now-5min, now],
     * 让恶意请求即使绕过 skipPush 门也无法把消息插到任意历史位置。
     */
    static LocalDateTime clampSendTime(LocalDateTime requested) {
        LocalDateTime now = LocalDateTime.now();
        if (requested.isAfter(now)) {
            return now;
        }
        LocalDateTime floor = now.minusMinutes(MAX_SEND_TIME_BACKDATE_MINUTES);
        if (requested.isBefore(floor)) {
            return floor;
        }
        return requested;
    }

    public static List<ChatMessageResp> buildMsgResp(List<Message> messages, List<MessageMark> msgMark, Long receiveUid) {
        Map<Long, List<MessageMark>> markMap = msgMark.stream().collect(Collectors.groupingBy(MessageMark::getMsgId));
        return messages.stream().map(a -> {
            ChatMessageResp resp = new ChatMessageResp();
            resp.setFromUser(buildFromUser(a.getFromUid()));
            resp.setMessage(buildMessage(a, markMap.getOrDefault(a.getId(), new ArrayList<>()), receiveUid));
            return resp;
        })
                .sorted(Comparator.comparing(a -> a.getMessage().getSendTime()))
                //帮前端排好序，更方便它展示
                .collect(Collectors.toList());
    }

    /**
     * REQ-004 S5: 把房间类型回填到已构建的 ChatMessageResp 上。
     * 域实体 {@link Message} 不持有 roomType，buildMsgResp 阶段拿不到 Room，
     * 故由持有 Room 的推送点（MsgSendConsumer）在 push 前调用本方法填充。
     * roomType 取值见 {@code RoomTypeEnum}（GROUP=1, FRIEND=2）。
     *
     * @param resp     已构建的聊天消息响应（可为 null，则不做任何事）
     * @param roomType 房间类型，来自 {@code Room#getType()}
     */
    public static void fillRoomType(ChatMessageResp resp, Integer roomType) {
        if (resp != null && resp.getMessage() != null) {
            resp.getMessage().setRoomType(roomType);
        }
    }

    /**
     * REQ-004 S23: 把发送者用户类型回填到已构建的 ChatMessageResp.fromUser 上。
     * 域实体 {@link Message} 只持有 fromUid，buildMsgResp 阶段拿不到 userType，
     * 故由持有用户缓存的 {@code ChatServiceImpl#getMsgRespBatch} 在返回前调用本方法填充。
     * userType 取值见 {@code UserTypeEnum}（SYSTEM=1, BOT=2, NORMAL=3, AICLAW=4）。
     * aiclaw 插件据 {@code fromUser.userType==4} 做 AI-to-AI 反环路与 respondToAi 判定。
     *
     * @param resp     已构建的聊天消息响应（resp 或 resp.fromUser 为 null 则不做任何事）
     * @param userType 发送者用户类型，来自 {@code SummeryInfoDTO#getUserType()}（可为 null）
     */
    public static void fillFromUserType(ChatMessageResp resp, Integer userType) {
        if (resp != null && resp.getFromUser() != null) {
            resp.getFromUser().setUserType(userType);
        }
    }

    /**
     * REQ-021: 把发送者显示名回填到已构建的 ChatMessageResp.fromUser 上。
     * 域实体 Message 只持有 fromUid，故由 ChatServiceImpl#getMsgRespBatch 解析「群昵称优先、回退用户名」后填充。
     * aiclaw 插件据此在群语境标注发言人，避免 [unknown(uid)]。
     * @param resp 已构建的聊天消息响应（resp 或 resp.fromUser 为 null 则不做任何事）
     * @param name 发送者显示名（可为 null）
     */
    public static void fillFromUserName(ChatMessageResp resp, String name) {
        if (resp != null && resp.getFromUser() != null) {
            resp.getFromUser().setName(name);
        }
    }

    private static ChatMessageResp.Message buildMessage(Message message, List<MessageMark> marks, Long receiveUid) {
        ChatMessageResp.Message messageVO = new ChatMessageResp.Message();
        BeanUtil.copyProperties(message, messageVO);
        messageVO.setSendTime(message.getCreateTime());
        AbstractMsgHandler<?> msgHandler = MsgHandlerFactory.getStrategyNoNull(message.getType());
        if (Objects.nonNull(msgHandler)) {
            messageVO.setBody(msgHandler.showMsg(message));
        }
        //消息标记
        messageVO.setMessageMarks(buildMsgMark(marks, receiveUid));
        return messageVO;
    }

	private static Map<Integer, ChatMessageResp.MarkItem> buildMsgMark(List<MessageMark> marks, Long receiveUid) {
		if(marks == null || marks.isEmpty()) return new HashMap<>();
		Map<Integer, List<MessageMark>> typeMap = marks.stream().collect(Collectors.groupingBy(MessageMark::getType));

		// 构造动态统计为主数据源
		Map<Integer, ChatMessageResp.MarkItem> stats = new HashMap<>();

		// 批量映射操作数量
		Arrays.stream(MessageMarkTypeEnum.values()).forEach(typeEnum -> {
			List<MessageMark> list = typeMap.getOrDefault(typeEnum.getType(), Collections.emptyList());

			stats.put(typeEnum.getType(), new ChatMessageResp.MarkItem(list.size(), Optional.ofNullable(receiveUid)
					.filter(uid -> list.stream().anyMatch(m -> m != null && Objects.equals(m.getUid(), uid)))
					.map(uid -> YesOrNoEnum.YES.getBool())
					.orElse(YesOrNoEnum.NO.getBool())));
		});

		return stats;
	}

    private static ChatMessageResp.UserInfo buildFromUser(Long fromUid) {
        ChatMessageResp.UserInfo userInfo = new ChatMessageResp.UserInfo();
        userInfo.setUid(fromUid.toString());
        return userInfo;
    }

	public static ChatMessageReq buildAgreeMsg(Long roomId, Boolean isPush) {
		ChatMessageReq chatMessageReq = new ChatMessageReq();
		chatMessageReq.setRoomId(roomId);
		chatMessageReq.setMsgType(MessageTypeEnum.TEXT.getType());
		chatMessageReq.setPushMessage(isPush);
		TextMsgReq textMsgReq = new TextMsgReq();
		textMsgReq.setContent("我们已经成为好友了，开始聊天吧");
		chatMessageReq.setBody(textMsgReq);
		return chatMessageReq;
	}

	/**
	 * 构造音视频消息
	 * @param callEndReq 构造参数
	 * @return
	 */
	public static ChatMessageReq buildMediumMsg(CallEndReq callEndReq) {
		ChatMessageReq chatMessageReq = new ChatMessageReq();
		chatMessageReq.setRoomId(callEndReq.getRoomId());
		chatMessageReq.setMsgType(callEndReq.getMediumType()? MessageTypeEnum.VIDEO_CALL.getType(): MessageTypeEnum.AUDIO_CALL.getType());
		chatMessageReq.setPushMessage(false);
		long duration = 0;
		if(Objects.nonNull(callEndReq.getStartTime()) && Objects.nonNull(callEndReq.getEndTime())){
			long time = callEndReq.getEndTime() - callEndReq.getStartTime();
			duration = (long) Math.ceil(time / 1000.0);
		}

		// 群聊消息
		if(callEndReq.getMediumType()){
			VideoCallMsgDTO videoCallMsgDTO = new VideoCallMsgDTO();
			videoCallMsgDTO.setBegin(callEndReq.getBegin());
			videoCallMsgDTO.setCreator(callEndReq.getUid());
			videoCallMsgDTO.setDuration(duration);
			videoCallMsgDTO.setIsGroup(callEndReq.getIsGroup());
			videoCallMsgDTO.setState(callEndReq.getState());
			videoCallMsgDTO.setStartTime(callEndReq.getStartTime());
			videoCallMsgDTO.setEndTime(callEndReq.getEndTime());
			chatMessageReq.setBody(videoCallMsgDTO);
		} else {
			AudioCallMsgDTO videoCallMsgDTO = new AudioCallMsgDTO();
			videoCallMsgDTO.setDuration(duration);
			videoCallMsgDTO.setIsGroup(callEndReq.getIsGroup());
			videoCallMsgDTO.setCreator(callEndReq.getUid());
			videoCallMsgDTO.setState(callEndReq.getState());
			videoCallMsgDTO.setStartTime(callEndReq.getStartTime());
			videoCallMsgDTO.setEndTime(callEndReq.getEndTime());
			chatMessageReq.setBody(videoCallMsgDTO);
		}

		return chatMessageReq;
	}

	public static ChatMessageReq buildAgreeMsg4Group(Long roomId, Long count, String userName) {
		ChatMessageReq chatMessageReq = new ChatMessageReq();
		chatMessageReq.setRoomId(roomId);
		chatMessageReq.setMsgType(MessageTypeEnum.BOT.getType());
		chatMessageReq.setSkip(true);
		TextMsgReq textMsgReq = new TextMsgReq();
		textMsgReq.setContent(String.format("欢迎[%s]第%d位加入HuLa", userName, count));
		chatMessageReq.setBody(textMsgReq);
		return chatMessageReq;
	}

	/**
	 * 合并消息
	 */
	public static ChatMessageReq buildMergeMsg(Long roomId, List<Message> messages) {
		ChatMessageReq chatMessageReq = new ChatMessageReq();
		chatMessageReq.setRoomId(roomId);
		chatMessageReq.setSkip(true);
		chatMessageReq.setMsgType(MessageTypeEnum.MERGE.getType());
		chatMessageReq.setBody(new MergeMsgDTO(messages.stream().map(msg -> new BodyDTO(msg.getFromUid().toString(), msg.getId().toString())).toList()));
		return chatMessageReq;
	}

	/**
	 * 群公告消息
	 */
	public static ChatMessageReq buildAnnouncementsMsg(Long roomId, Announcements announcements) {
		ChatMessageReq chatMessageReq = new ChatMessageReq();
		chatMessageReq.setRoomId(roomId);
		chatMessageReq.setSkip(true);
		chatMessageReq.setMsgType(MessageTypeEnum.NOTICE.getType());
		NoticeMsgDTO noticeMsgDTO = new NoticeMsgDTO();
		noticeMsgDTO.setId(announcements.getId());
		noticeMsgDTO.setUid(announcements.getUid());
		noticeMsgDTO.setTop(announcements.getTop());
		noticeMsgDTO.setRoomId(announcements.getRoomId());
		noticeMsgDTO.setContent(announcements.getContent());
		noticeMsgDTO.setCreateTime(TimeUtils.getTime(announcements.getUpdateTime()));
		chatMessageReq.setBody(noticeMsgDTO);
		return chatMessageReq;
	}

	/**
	 * 邀请用户进群通知
	 * @param resp 通知数据
	 */
	public static WsBaseResp<WSNotice> buildInviteeUserAddGroupMessage(WSNotice resp) {
		WsBaseResp<WSNotice> wsBaseResp = new WsBaseResp<>();
		wsBaseResp.setType(WSRespTypeEnum.NEW_APPLY.getType());
		wsBaseResp.setData(resp);
		return wsBaseResp;
	}

	/**
	 * 构建设置管理员
	 */
	public static WsBaseResp<AdminChangeDTO> buildSetAdminMessage(AdminChangeDTO adminChangeDTO) {
		WsBaseResp<AdminChangeDTO> wsBaseResp = new WsBaseResp<>();
		wsBaseResp.setType(WSRespTypeEnum.GROUP_SET_ADMIN.getType());
		wsBaseResp.setData(adminChangeDTO);
		return wsBaseResp;
	}

	/**
	 * 已读群公告
	 */
	public static WsBaseResp<ReadAnnouncementsResp> buildReadRoomGroupAnnouncement(ReadAnnouncementsResp resp) {
		WsBaseResp<ReadAnnouncementsResp> wsBaseResp = new WsBaseResp<>();
		wsBaseResp.setType(WSRespTypeEnum.ROOM_GROUP_NOTICE_READ_MSG.getType());
		wsBaseResp.setData(resp);
		return wsBaseResp;
	}

}
