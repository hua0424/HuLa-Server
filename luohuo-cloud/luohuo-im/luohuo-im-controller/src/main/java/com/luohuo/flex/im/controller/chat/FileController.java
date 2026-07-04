package com.luohuo.flex.im.controller.chat;

import com.luohuo.basic.base.R;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.common.utils.MinioPresigner;
import com.luohuo.flex.common.utils.StorageUrlUtil;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.im.core.chat.service.AiclawRoomMembershipService;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.entity.msg.MessageExtra;
import com.luohuo.flex.im.domain.vo.req.file.SignDownloadReq;
import com.luohuo.flex.im.domain.vo.response.msg.BaseFileDTO;
import com.luohuo.flex.im.domain.vo.resp.file.SignDownloadResp;
import com.luohuo.flex.service.SysConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * sign-on-access(#146)：文件按需下载签名接口。
 *
 * <p>网关面路径 {@code /im/file/sign-download}（{@code /im} 前缀由网关路由补上，与其它 im 控制器一致）。
 * 老消息只落了一条长效预签名 url，新消息落 objectKey；本端点按需为「当前认证用户可见的消息」
 * 生成一条短效预签名 GET，避免长期暴露可下载地址。</p>
 *
 * @author sign-on-access (aichatoverview#146)
 */
@Slf4j
@RestController
@RequestMapping("/file")
@Tag(name = "文件按需下载签名")
public class FileController {

    @Resource
    private MessageDao messageDao;

    @Resource
    private AiclawRoomMembershipService roomMembershipService;

    @Resource
    private SysConfigService sysConfigService;

    @PostMapping("/sign-download")
    @Operation(summary = "为消息中的文件/图片按需生成短效预签名下载地址")
    public R<SignDownloadResp> signDownload(@Valid @RequestBody SignDownloadReq req) {
        Long uid = ContextUtil.getUid();

        // 1. 载入消息
        Message message = messageDao.getById(req.getMsgId());
        if (message == null) {
            throw new BizException("消息不存在");
        }

        // 2. 成员校验：调用者必须是该房间成员（非成员时抛 BizException = 403 路径）
        roomMembershipService.checkMembership(uid, message.getRoomId());

        // 3. 从消息体派生 objectKey（新消息 objectKey 优先，老消息回退从 url 解析）
        BaseFileDTO body = fileBody(message.getExtra());
        if (body == null) {
            throw new BizException("消息不含可下载文件");
        }
        String bucket = sysConfigService.get("minioBucket");
        String objectKey = hasText(body.getObjectKey())
                ? body.getObjectKey()
                : StorageUrlUtil.parseObjectKeyFromUrl(body.getUrl(), bucket);
        if (!hasText(objectKey)) {
            throw new BizException("无法解析文件对象，无法生成下载地址");
        }

        // 4. 短效预签名 GET；先解析有效期再签名，保证返回的 expiresIn 与 url 生命周期一致
        int expiresIn = MinioPresigner.resolveSignExpiry(sysConfigService.get("minioSignExpiry"), 0);
        String url = MinioPresigner.presignGet(minioConfig(bucket), objectKey, expiresIn);
        return R.success(SignDownloadResp.builder().url(url).expiresIn(expiresIn).build());
    }

    /**
     * 取消息体中的文件/图片（二者取非空者；文件优先）。
     */
    private BaseFileDTO fileBody(MessageExtra extra) {
        if (extra == null) {
            return null;
        }
        if (extra.getFileMsg() != null) {
            return extra.getFileMsg();
        }
        return extra.getImgMsgDTO();
    }

    private Map<String, String> minioConfig(String bucket) {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("minioEndpoint", sysConfigService.get("minioEndpoint"));
        cfg.put("minioAccessKey", sysConfigService.get("minioAccessKey"));
        cfg.put("minioSecretKey", sysConfigService.get("minioSecretKey"));
        cfg.put("minioBucket", bucket);
        cfg.put("minioRegion", sysConfigService.get("minioRegion"));
        return cfg;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
