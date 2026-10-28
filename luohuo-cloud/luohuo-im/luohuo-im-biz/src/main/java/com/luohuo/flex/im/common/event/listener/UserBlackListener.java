package com.luohuo.flex.im.common.event.listener;

import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.domain.entity.User;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import com.luohuo.flex.im.common.event.UserBlackEvent;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.model.entity.WSRespTypeEnum;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.WSBlack;
import com.luohuo.flex.im.core.user.service.dto.PushSubmission;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.im.api.DefUserApi;

import static com.luohuo.flex.im.common.config.ThreadPoolConfig.LUOHUO_EXECUTOR;

/**
 * 用户拉黑监听器
 *
 * @author nyh
 */
@Slf4j
@Component
@AllArgsConstructor
public class UserBlackListener {

    private final MessageDao messageDao;
    private final UserSummaryCache userSummaryCache;
    private final PushService pushService;
    private final DefUserApi defUserApi;

    @Async(LUOHUO_EXECUTOR)
    @EventListener(classes = UserBlackEvent.class)
    public void refreshRedis(UserBlackEvent event) {
		userSummaryCache.evictBlackMap();
    }

    @Async(LUOHUO_EXECUTOR)
    @EventListener(classes = UserBlackEvent.class)
    public void deleteMsg(UserBlackEvent event) {
        messageDao.invalidByUid(event.getUser().getId());
    }

    @Async(LUOHUO_EXECUTOR)
    @EventListener(classes = UserBlackEvent.class)
    public void sendPush(UserBlackEvent event) {
        User user = event.getUser();
        Long uid = user.getId();
        WsBaseResp<WSBlack> resp = new WsBaseResp<>();
        WSBlack black = new WSBlack(uid);
        resp.setData(black);
        resp.setType(WSRespTypeEnum.INVALID_USER.getType());
        // #482：推送被拒不得阻断强制下线；推送已接收也不代表会话已失效，两者独立记录。
        PushSubmission submission = pushService.submitPushMsg(resp, uid, ContextUtil.getUid());
        if (submission != null && !submission.isFullyAdmitted()) {
            log.info("拉黑通知推送未全部接收: uid={}, admission={}, routeStatus={}, 未接收={}",
                    uid, submission.getAdmission(), submission.getRouteStatus(), submission.getUnreceivedUids());
        }
        try {
            defUserApi.logout(uid, null);
        } catch (Exception e) {
            log.error("黑名单强制下线失败, uid={}", uid, e);
        }
    }
}
