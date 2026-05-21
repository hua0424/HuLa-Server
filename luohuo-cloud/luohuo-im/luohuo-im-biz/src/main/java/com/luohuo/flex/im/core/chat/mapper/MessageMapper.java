package com.luohuo.flex.im.core.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luohuo.flex.im.domain.entity.Contact;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;
import com.luohuo.flex.im.domain.entity.Message;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 消息表 Mapper 接口
 * </p>
 *
 * @author nyh
 */
@Repository
public interface MessageMapper extends BaseMapper<Message> {

	List<Map<String, Object>> batchGetUnReadCount(@Param("uid") Long uid, @Param("contactList") Collection<Contact> contactList);

	/**
	 * REQ-004 M3: 查询发送者在房间最近 N 条消息的内容长度
	 */
	List<Integer> selectRecentMsgLengths(@Param("fromUid") Long fromUid, @Param("roomId") Long roomId, @Param("limit") int limit);
}
