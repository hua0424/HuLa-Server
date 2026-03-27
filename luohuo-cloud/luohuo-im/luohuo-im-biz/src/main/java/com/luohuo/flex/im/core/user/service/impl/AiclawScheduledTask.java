package com.luohuo.flex.im.core.user.service.impl;

import com.luohuo.flex.im.core.user.service.AiclawService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * aiclaw 定时任务：每小时扫描已停用超过 24h 的 aiclaw 并彻底注销
 */
@Slf4j
@Component
@AllArgsConstructor
public class AiclawScheduledTask {

	private final AiclawService aiclawService;

	@Scheduled(cron = "0 0 * * * *")
	public void purgeExpiredDeactivatedAiclaws() {
		log.debug("running aiclaw deactivation purge task");
		try {
			com.luohuo.basic.context.ContextUtil.setTenantId(1L);
			aiclawService.purgeExpiredDeactivated();
		} catch (Exception e) {
			log.error("aiclaw purge task failed", e);
		}
	}
}
