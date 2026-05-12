package com.luohuo.flex.im.core.chat.dao;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.core.chat.mapper.RoomMapper;
import com.luohuo.flex.im.domain.vo.response.MemberResp;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 房间表 服务实现类
 * </p>
 *
 * @author nyh
 */
@Service
public class RoomDao extends ServiceImpl<RoomMapper, Room> implements IService<Room> {

    /**
     * ISS-005: 推进 Room 的 last_msg_id / active_time(只前进,不回退)。
     * 走 {@link RoomMapper#refreshActiveTime} 的 IF 单调保护 SQL,与 ISS-004 的 ContactMapper 同构。
     *
     * <p>调用方:
     * <ul>
     *   <li>{@code ChatServiceImpl.sendMsg} — 同事务 sync 写入,覆盖 skipPush=true(stream_end)路径</li>
     *   <li>{@code MsgSendConsumer.onMessage} — MQ 异步路径,与 sync 写入竞态时由 IF 兜底</li>
     * </ul>
     */
    public void refreshActiveTime(Long roomId, Long msgId, LocalDateTime msgTime) {
        baseMapper.refreshActiveTime(roomId, msgId, msgTime);
    }

    public List<MemberResp> groupList(Long uid) {
       	return baseMapper.groupList(uid);
    }

    public List<MemberResp> getAllGroupList() {
        return baseMapper.getAllGroupList();
    }
}
