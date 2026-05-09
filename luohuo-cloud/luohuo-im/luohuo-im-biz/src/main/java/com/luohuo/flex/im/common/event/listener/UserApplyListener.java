package com.luohuo.flex.im.common.event.listener;

import com.luohuo.flex.im.core.user.dao.AiclawDao;
import com.luohuo.flex.im.core.user.dao.NoticeDao;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.domain.dto.SummeryInfoDTO;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import com.luohuo.flex.im.enums.UserTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import com.luohuo.flex.im.common.event.UserApplyEvent;
import com.luohuo.flex.im.domain.entity.UserApply;
import com.luohuo.flex.model.entity.ws.WSNotice;
import com.luohuo.flex.im.core.user.service.adapter.WsAdapter;
import com.luohuo.flex.im.core.user.service.impl.PushService;

import java.util.Objects;

import static com.luohuo.flex.im.common.config.ThreadPoolConfig.LUOHUO_EXECUTOR;

/**
 * 好友申请监听器
 *
 * @author zhongzb create on 2022/08/26
 */
@Slf4j
@Component
public class UserApplyListener {
	@Resource
	private NoticeDao noticeDao;

    @Resource
    private PushService pushService;

	@Resource
	private UserSummaryCache userSummaryCache;

	@Resource
	private AiclawDao aiclawDao;

    @Async(LUOHUO_EXECUTOR)
    @TransactionalEventListener(classes = UserApplyEvent.class, fallbackExecution = true)
    public void notifyFriend(UserApplyEvent event) {
        UserApply userApply = event.getUserApply();
		Long targetId = userApply.getTargetId();

		// 如果 target 是 aiclaw，推送给 owner 而非 aiclaw 本身
		Long pushToUid = targetId;
		SummeryInfoDTO targetInfo = userSummaryCache.get(targetId);
		if (targetInfo != null && Objects.equals(targetInfo.getUserType(), UserTypeEnum.AICLAW.getValue())) {
			Aiclaw aiclaw = aiclawDao.getByUid(targetId);
			if (aiclaw != null) {
				pushToUid = aiclaw.getOwnerUid();
			}
		}

		WSNotice resp = noticeDao.getUnReadCount(userApply.getUid(), pushToUid);
        pushService.sendPushMsg(WsAdapter.buildApplySend(resp), pushToUid, userApply.getUid());
    }

}
