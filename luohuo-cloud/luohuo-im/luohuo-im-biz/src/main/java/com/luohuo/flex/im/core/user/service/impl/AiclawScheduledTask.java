package com.luohuo.flex.im.core.user.service.impl;

import com.luohuo.flex.im.core.user.service.AiclawService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * aiclaw 定时任务：每分钟扫描已停用超过配置保留时长的 aiclaw 并彻底注销
 * （保留时长由 base_config 的 aiclaw.deactivate.retention.minutes 配置化，见 AiclawServiceImpl#getDeactivateRetentionMinutes）
 */
@Slf4j
@Component
@AllArgsConstructor
public class AiclawScheduledTask {

	private final AiclawService aiclawService;

	@Scheduled(cron = "0 * * * * *")
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
