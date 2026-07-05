package com.luohuo.flex.im.core.chat.service.strategy.msg;

import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.entity.msg.MessageExtra;
import com.luohuo.flex.im.domain.vo.response.msg.EmojisMsgDTO;
import com.luohuo.flex.im.domain.vo.response.msg.FileMsgDTO;
import com.luohuo.flex.im.domain.vo.response.msg.ImgMsgDTO;
import com.luohuo.flex.im.domain.vo.response.msg.VideoMsgDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;

/**
 * #149：文件/图片/视频/表情消息 saveMsg 必须把 body.atUidList **上提**到 extra 顶层
 * （与 TextMsgHandler 一致）——否则群里媒体消息 @aiclaw 不触发、@真人 不高亮。
 * 覆盖两分支：有 @ → extra.atUidList == body.atUidList；无 @ → extra.atUidList 保持 null。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaMsgHandlerAtUidListTest {

    @Mock
    private MessageDao messageDao;

    @InjectMocks
    private FileMsgHandler fileMsgHandler;
    @InjectMocks
    private ImgMsgHandler imgMsgHandler;
    @InjectMocks
    private VideoMsgHandler videoMsgHandler;
    @InjectMocks
    private EmojisMsgHandler emojisMsgHandler;

    private static final List<Long> AT = List.of(140789091499520L, 163589881742848L);

    private Message baseMsg() {
        Message m = Message.builder().roomId(1L).fromUid(100L).extra(new MessageExtra()).build();
        m.setId(999L);
        return m;
    }

    private MessageExtra persistedExtra() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageDao).updateById(captor.capture());
        return captor.getValue().getExtra();
    }

    @Test
    @DisplayName("File 有 @ → 上提到 extra.atUidList")
    void fileHoists() {
        FileMsgDTO b = new FileMsgDTO();
        b.setAtUidList(AT);
        fileMsgHandler.saveMsg(baseMsg(), b);
        assertEquals(AT, persistedExtra().getAtUidList());
    }

    @Test
    @DisplayName("File 无 @ → extra.atUidList 保持 null（不误置空列表/回归）")
    void fileNoAt() {
        fileMsgHandler.saveMsg(baseMsg(), new FileMsgDTO());
        assertNull(persistedExtra().getAtUidList());
    }

    @Test
    @DisplayName("Img 有 @ → 上提")
    void imgHoists() {
        ImgMsgDTO b = new ImgMsgDTO();
        b.setAtUidList(AT);
        imgMsgHandler.saveMsg(baseMsg(), b);
        assertEquals(AT, persistedExtra().getAtUidList());
    }

    @Test
    @DisplayName("Img 无 @ → null")
    void imgNoAt() {
        imgMsgHandler.saveMsg(baseMsg(), new ImgMsgDTO());
        assertNull(persistedExtra().getAtUidList());
    }

    @Test
    @DisplayName("Video 有 @ → 上提")
    void videoHoists() {
        VideoMsgDTO b = new VideoMsgDTO();
        b.setAtUidList(AT);
        videoMsgHandler.saveMsg(baseMsg(), b);
        assertEquals(AT, persistedExtra().getAtUidList());
    }

    @Test
    @DisplayName("Video 无 @ → null")
    void videoNoAt() {
        videoMsgHandler.saveMsg(baseMsg(), new VideoMsgDTO());
        assertNull(persistedExtra().getAtUidList());
    }

    @Test
    @DisplayName("Emojis 有 @ → 上提")
    void emojisHoists() {
        EmojisMsgDTO b = new EmojisMsgDTO();
        b.setAtUidList(AT);
        emojisMsgHandler.saveMsg(baseMsg(), b);
        assertEquals(AT, persistedExtra().getAtUidList());
    }

    @Test
    @DisplayName("Emojis 无 @ → null")
    void emojisNoAt() {
        emojisMsgHandler.saveMsg(baseMsg(), new EmojisMsgDTO());
        assertNull(persistedExtra().getAtUidList());
    }
}
