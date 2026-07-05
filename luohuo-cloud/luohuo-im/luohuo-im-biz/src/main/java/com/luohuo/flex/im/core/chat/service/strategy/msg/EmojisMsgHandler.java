package com.luohuo.flex.im.core.chat.service.strategy.msg;

import cn.hutool.core.collection.CollectionUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.vo.response.msg.EmojisMsgDTO;
import com.luohuo.flex.im.domain.entity.msg.MessageExtra;
import com.luohuo.flex.im.domain.enums.MessageTypeEnum;

import java.util.Optional;

/**
 * 表情消息
 * @author nyh
 */
@Component
public class EmojisMsgHandler extends AbstractMsgHandler<EmojisMsgDTO> {
    @Resource
    private MessageDao messageDao;

    @Override
    MessageTypeEnum getMsgTypeEnum() {
        return MessageTypeEnum.EMOJI;
    }

    @Override
    public void saveMsg(Message msg, EmojisMsgDTO body) {
        MessageExtra extra = Optional.ofNullable(msg.getExtra()).orElse(new MessageExtra());
        Message update = new Message();
        update.setId(msg.getId());
        update.setExtra(extra);
		update.setReplyMsgId(body.getReplyMsgId());
        extra.setEmojisMsgDTO(body);
        // #149：上提 @ 列表到 extra 顶层（与 TextMsgHandler 一致）——否则群里表情消息 @aiclaw 不触发、@真人 不高亮。
        if (CollectionUtil.isNotEmpty(body.getAtUidList())) {
            extra.setAtUidList(body.getAtUidList());
        }
        messageDao.updateById(update);
    }

    @Override
    public Object showMsg(Message msg) {
		EmojisMsgDTO resp = msg.getExtra().getEmojisMsgDTO();
		resp.setAtUidList(Optional.ofNullable(msg.getExtra()).map(MessageExtra::getAtUidList).orElse(null));
		resp.setReply(replyMsg(msg));
		return resp;
    }

    @Override
    public Object showReplyMsg(Message msg) {
        return "表情";
    }

    @Override
    public String showContactMsg(Message msg) {
        return "[动画表情]";
    }
}
