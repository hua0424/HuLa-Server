package com.luohuo.flex.im.controller.chat;

import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.common.utils.MinioPresigner;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.im.core.chat.service.AiclawRoomMembershipService;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.entity.msg.MessageExtra;
import com.luohuo.flex.im.domain.vo.response.msg.FileMsgDTO;
import com.luohuo.flex.im.domain.vo.response.msg.ImgMsgDTO;
import com.luohuo.flex.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * sign-on-access(#146)：{@link FileController#signDownload} 接线层单元测试。
 *
 * <p>纯 mock（MessageDao / AiclawRoomMembershipService / SysConfigService + 静态
 * {@code ContextUtil.getUid()} / {@code MinioPresigner}），直接调用 controller 方法，
 * 锁住四条路径：新消息 happy path、非成员拒绝、objectKey 缺失 not-found、老消息 url 回退。</p>
 */
class FileControllerTest {

    private static final Long UID = 1001L;
    private static final Long MSG_ID = 555L;
    private static final Long ROOM_ID = 42L;
    private static final String BUCKET = "hula-bucket";
    private static final String SIGNED_URL = "http://127.0.0.1:9000/" + BUCKET + "/ai/x.png?X-Amz-Signature=abc";
    private static final int RESOLVED_EXPIRY = 300;

    private MessageDao messageDao;
    private AiclawRoomMembershipService roomMembershipService;
    private SysConfigService sysConfigService;
    private FileController controller;

    @BeforeEach
    void setUp() {
        messageDao = mock(MessageDao.class);
        roomMembershipService = mock(AiclawRoomMembershipService.class);
        sysConfigService = mock(SysConfigService.class);
        controller = new FileController();
        ReflectionTestUtils.setField(controller, "messageDao", messageDao);
        ReflectionTestUtils.setField(controller, "roomMembershipService", roomMembershipService);
        ReflectionTestUtils.setField(controller, "sysConfigService", sysConfigService);
        when(sysConfigService.get("minioBucket")).thenReturn(BUCKET);
    }

    private com.luohuo.flex.im.domain.vo.req.file.SignDownloadReq req() {
        com.luohuo.flex.im.domain.vo.req.file.SignDownloadReq r =
                new com.luohuo.flex.im.domain.vo.req.file.SignDownloadReq();
        r.setMsgId(MSG_ID);
        return r;
    }

    private Message messageWithFile(String objectKey, String url) {
        FileMsgDTO file = new FileMsgDTO();
        file.setObjectKey(objectKey);
        file.setUrl(url);
        MessageExtra extra = MessageExtra.builder().fileMsg(file).build();
        Message m = new Message();
        m.setRoomId(ROOM_ID);
        m.setExtra(extra);
        return m;
    }

    @Test
    @DisplayName("新消息 happy path：成员 + body.objectKey → 用该 objectKey 与解析出的 expiry 签名")
    void happyPathNewMessage() {
        when(messageDao.getById(MSG_ID)).thenReturn(messageWithFile("ai/2026/06/photo.png", null));

        try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class);
             MockedStatic<MinioPresigner> presigner = mockStatic(MinioPresigner.class)) {
            ctx.when(ContextUtil::getUid).thenReturn(UID);
            presigner.when(() -> MinioPresigner.resolveSignExpiry(any(), anyInt())).thenReturn(RESOLVED_EXPIRY);
            presigner.when(() -> MinioPresigner.presignGet(any(), eq("ai/2026/06/photo.png"), eq(RESOLVED_EXPIRY)))
                    .thenReturn(SIGNED_URL);

            var resp = controller.signDownload(req());

            assertTrue(resp.getsuccess(), "must succeed");
            assertEquals(SIGNED_URL, resp.getData().getUrl());
            assertEquals(RESOLVED_EXPIRY, resp.getData().getExpiresIn());
            // 成员校验用的是认证 uid + 消息 roomId
            verify(roomMembershipService).checkMembership(UID, ROOM_ID);
            // 用 objectKey + 已解析的 expiry 签名（expiresIn 与 url 生命周期一致）
            presigner.verify(() -> MinioPresigner.presignGet(any(), eq("ai/2026/06/photo.png"), eq(RESOLVED_EXPIRY)));
        }
    }

    @Test
    @DisplayName("非成员：checkMembership 抛 BizException → 端点抛出且不签名")
    void nonMemberRejected() {
        when(messageDao.getById(MSG_ID)).thenReturn(messageWithFile("ai/x.png", null));
        doThrow(new BizException("非房间成员，无法发送消息"))
                .when(roomMembershipService).checkMembership(UID, ROOM_ID);

        try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class);
             MockedStatic<MinioPresigner> presigner = mockStatic(MinioPresigner.class)) {
            ctx.when(ContextUtil::getUid).thenReturn(UID);

            assertThrows(BizException.class, () -> controller.signDownload(req()));

            presigner.verify(() -> MinioPresigner.presignGet(any(), any(), anyInt()), never());
        }
    }

    @Test
    @DisplayName("objectKey 缺失：成员但 fileMsg/imgMsgDTO 均无可解析 key → not-found，不签名")
    void objectKeyMissingNotFound() {
        Message m = new Message();
        m.setRoomId(ROOM_ID);
        m.setExtra(MessageExtra.builder().build()); // 无文件/图片体
        when(messageDao.getById(MSG_ID)).thenReturn(m);

        try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class);
             MockedStatic<MinioPresigner> presigner = mockStatic(MinioPresigner.class)) {
            ctx.when(ContextUtil::getUid).thenReturn(UID);

            assertThrows(BizException.class, () -> controller.signDownload(req()));

            verify(roomMembershipService).checkMembership(UID, ROOM_ID);
            presigner.verify(() -> MinioPresigner.presignGet(any(), any(), anyInt()), never());
        }
    }

    @Test
    @DisplayName("老消息回退：body.objectKey 为空但 url 是老预签名 → 从 url 解析 key 再签名")
    void oldMessageUrlFallback() {
        String oldUrl = "http://127.0.0.1:9000/" + BUCKET + "/chat/1717_photo.png?X-Amz-Expires=604800";
        when(messageDao.getById(MSG_ID)).thenReturn(messageWithFile(null, oldUrl));

        try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class);
             MockedStatic<MinioPresigner> presigner = mockStatic(MinioPresigner.class)) {
            ctx.when(ContextUtil::getUid).thenReturn(UID);
            presigner.when(() -> MinioPresigner.resolveSignExpiry(any(), anyInt())).thenReturn(RESOLVED_EXPIRY);
            presigner.when(() -> MinioPresigner.presignGet(any(), eq("chat/1717_photo.png"), eq(RESOLVED_EXPIRY)))
                    .thenReturn(SIGNED_URL);

            var resp = controller.signDownload(req());

            assertTrue(resp.getsuccess());
            assertEquals(SIGNED_URL, resp.getData().getUrl());
            // 从老 url 回推出的 objectKey 被喂给 presigner
            presigner.verify(() -> MinioPresigner.presignGet(any(), eq("chat/1717_photo.png"), eq(RESOLVED_EXPIRY)));
        }
    }

    @Test
    @DisplayName("图片消息（imgMsgDTO）同样可签名")
    void imageMessageAlsoSigns() {
        ImgMsgDTO img = new ImgMsgDTO();
        img.setObjectKey("chat/pic.png");
        MessageExtra extra = MessageExtra.builder().imgMsgDTO(img).build();
        Message m = new Message();
        m.setRoomId(ROOM_ID);
        m.setExtra(extra);
        when(messageDao.getById(MSG_ID)).thenReturn(m);

        try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class);
             MockedStatic<MinioPresigner> presigner = mockStatic(MinioPresigner.class)) {
            ctx.when(ContextUtil::getUid).thenReturn(UID);
            presigner.when(() -> MinioPresigner.resolveSignExpiry(any(), anyInt())).thenReturn(RESOLVED_EXPIRY);
            presigner.when(() -> MinioPresigner.presignGet(any(), eq("chat/pic.png"), eq(RESOLVED_EXPIRY)))
                    .thenReturn(SIGNED_URL);

            var resp = controller.signDownload(req());

            assertTrue(resp.getsuccess());
            presigner.verify(() -> MinioPresigner.presignGet(any(), eq("chat/pic.png"), eq(RESOLVED_EXPIRY)));
        }
    }
}
