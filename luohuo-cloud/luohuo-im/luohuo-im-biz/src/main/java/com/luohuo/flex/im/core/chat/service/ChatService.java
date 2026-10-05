package com.luohuo.flex.im.core.chat.service;

import com.luohuo.flex.im.domain.vo.request.*;
import com.luohuo.flex.im.domain.vo.res.CursorPageBaseResp;
import com.luohuo.flex.im.domain.vo.res.MsgWindowResp;
import com.luohuo.flex.im.domain.dto.MsgReadInfoDTO;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.vo.response.ChatMessageReadResp;
import com.luohuo.flex.model.entity.ws.ChatMessageResp;
import jakarta.annotation.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * 消息处理类
 * @author nyh
 */
public interface ChatService {

    /**
     * 发送消息
     */
    Long sendMsg(ChatMessageReq request, Long uid);

	/**
	 * 获取所有消息
	 */
	List<ChatMessageResp> getMsgList(MsgReq msgReq, Long receiveUid);

    /**
     * 根据消息获取消息前端展示的物料
     *
     * @param message 消息
     * @param receiveUid 接受消息的uid，可null
     */
    ChatMessageResp getMsgResp(Message message, Long receiveUid);

    /**
     * 根据消息获取消息前端展示的物料
     *
     * @param msgId 消息id
     * @param receiveUid 接受消息的uid，可null
     */
    ChatMessageResp getMsgResp(Long msgId, Long receiveUid);

    /**
     * 获取消息列表
     *
     * @param request
     */
    CursorPageBaseResp<ChatMessageResp> getMsgPage(ChatMessagePageReq request, @Nullable Long receiveUid);

    /**
     * aichatoverview#350：当前阅读窗口校准。
     *
     * <p>范围成员 + 逐已知 ID 回执在同一短 RR 一致性读内完成；只读权威库，
     * 不经可能滞后的消息缓存判断存在或权限；不新增 DDL。
     */
    MsgWindowResp getMsgWindow(MsgWindowReq request, Long receiveUid);

    void setMsgMark(Long uid, ChatMessageMarkReq request);

    void recallMsg(Long uid, ChatMessageBaseReq request);

    Collection<MsgReadInfoDTO> getMsgReadInfo(Long uid, ChatMessageReadInfoReq request);

    CursorPageBaseResp<ChatMessageReadResp> getReadPage(Long uid, ChatMessageReadReq request);

    void msgRead(Long uid, ChatMessageMemberReq request);

	List<Message> getMsgByIds(List<Long> messageIds);

	/**
	 * 创建用户与房间的会话
	 * @param uid 需要创建会话的uid
	 * @param roomId 创建的房间
	 */
	void createContact(Long uid, Long roomId);
}
