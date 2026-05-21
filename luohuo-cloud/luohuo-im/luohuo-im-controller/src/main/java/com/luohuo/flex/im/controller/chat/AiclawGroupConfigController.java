package com.luohuo.flex.im.controller.chat;

import com.luohuo.basic.base.R;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.im.core.chat.service.AiclawGroupConfigService;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawGroupConfigUpdateReq;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawGroupConfigResp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * aiclaw 群聊配置管理
 */
@RestController
@RequestMapping("/aiclaw/group/config")
@Tag(name = "aiclaw 群聊配置")
public class AiclawGroupConfigController {

	@Resource
	private AiclawGroupConfigService aiclawGroupConfigService;

	@GetMapping
	@Operation(summary = "查询 aiclaw 在指定群的配置")
	public R<AiclawGroupConfigResp> getConfig(
			@RequestParam Long aiclawUid,
			@RequestParam Long roomId) {
		return R.success(aiclawGroupConfigService.getConfig(aiclawUid, roomId, ContextUtil.getUid()));
	}

	@PutMapping
	@Operation(summary = "更新 aiclaw 群配置（仅主人）")
	public R<Void> updateConfig(@Valid @RequestBody AiclawGroupConfigUpdateReq request) {
		aiclawGroupConfigService.updateConfig(request, ContextUtil.getUid());
		return R.success();
	}
}
