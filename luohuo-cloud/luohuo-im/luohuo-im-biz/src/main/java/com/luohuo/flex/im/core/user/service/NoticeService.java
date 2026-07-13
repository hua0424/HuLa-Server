package com.luohuo.flex.im.core.user.service;

import com.luohuo.flex.im.domain.entity.Notice;
import com.luohuo.flex.im.domain.enums.NoticeTypeEnum;
import com.luohuo.flex.im.domain.enums.RoomTypeEnum;
import com.luohuo.flex.im.domain.vo.req.NoticeReq;
import com.luohuo.flex.im.domain.vo.res.NoticeVO;
import com.luohuo.flex.im.domain.vo.res.PageBaseResp;
import com.luohuo.flex.model.entity.ws.WSNotice;

/**
 * 系统通知服务，群通知、好友通知
 */
public interface NoticeService {

	Notice getByApplyId(Long uid, Long applyId);

	/**
	 * 创建通知
	 * @param applyType 通知类型 1群聊 2单聊
	 * @param type 具体的事件类型
	 * @param senderId 发起人
	 * @param receiverId 接收人UID
	 * @param applyId 申请ID
	 * @param operate 被操作的人
	 * @param content 消息内容
	 */
	void createNotice(RoomTypeEnum applyType, NoticeTypeEnum type, Long senderId, Long receiverId, Long applyId, Long operate, String content);

	void createNotice(RoomTypeEnum applyType, NoticeTypeEnum type, Long senderId, Long receiverId, Long applyId, Long operate, Long roomId, String content);

	/**
	 * 构建一条通知实体（不落库、不推送）。
	 * <p>字段装配与 {@link #createNotice(RoomTypeEnum, NoticeTypeEnum, Long, Long, Long, Long, Long, String)}
	 * 完全一致，供批量保存路径复用（先 build 收集、再 {@link #createNotices(java.util.List)} 一次落库 + 逐条推送）。</p>
	 */
	Notice buildNotice(RoomTypeEnum applyType, NoticeTypeEnum type, Long senderId, Long receiverId, Long applyId, Long operate, Long roomId, String content);

	/**
	 * 批量保存通知并逐条实时推送。
	 * <p>= 一次 {@code saveBatch}（回填每条 id）+ 对每条 {@code pushNoticeToUser}。
	 * 推送条数与逐条 {@code createNotice} 完全一致，只是把 N 次 INSERT 合并成 1 次批量 INSERT。</p>
	 */
	void createNotices(java.util.List<Notice> notices);

	/**
	 * 更新通知状态
	 * @param notice
	 */
	void updateNotice(Notice notice);

	/**
	 * 批量更新通知状态
	 * @param notice
	 */
	void updateNotices(Notice notice);

	/**
	 * 获取用户通知
	 */
	PageBaseResp<NoticeVO> getUserNotices(Long userId, NoticeReq request);

	/**
	 * 标记为已读
	 */
	void markAsRead(Long noticeId);

	/**
	 * 查询未读数
	 */
	WSNotice unread(Long uid);
}
