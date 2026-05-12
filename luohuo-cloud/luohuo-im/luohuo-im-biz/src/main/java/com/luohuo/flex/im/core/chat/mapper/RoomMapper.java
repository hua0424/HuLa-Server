package com.luohuo.flex.im.core.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luohuo.flex.im.domain.vo.response.MemberResp;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;
import com.luohuo.flex.im.domain.entity.Room;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 房间表 Mapper 接口
 * </p>
 *
 * @author nyh
 */
@Repository
public interface RoomMapper extends BaseMapper<Room> {

    List<MemberResp> groupList(Long uid);

    List<MemberResp> getAllGroupList();

    /**
     * ISS-005: 推进房间的 last_msg_id / active_time(只前进,不回退)。
     * 与 ISS-004 的 ContactMapper.refreshOrCreateActiveTime 同构,IF 单调保护应对:
     * - sendMsg 同事务 sync 写入 与 MsgSendConsumer 异步写入的并发
     * - skipPush=true 路径(stream_end)由 sendMsg 兜底,不再依赖 MsgSendConsumer
     */
    void refreshActiveTime(@Param("roomId") Long roomId, @Param("msgId") Long msgId, @Param("msgTime") LocalDateTime msgTime);
}
