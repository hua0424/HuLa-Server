package com.luohuo.flex.im.controller.chat;

import com.luohuo.basic.base.R;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.im.core.chat.service.AiclawCommandPolicyService;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawCommandPolicyUpdateReq;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawCommandPolicyResp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * aichatoverview#504 Agent 命令权限管理（仅主人可见、可写）。
 */
@RestController
@RequestMapping("/aiclaw/command/policy")
@Tag(name = "aichatoverview#504 Agent 命令权限")
public class AiclawCommandPolicyController {

	@Resource
	private AiclawCommandPolicyService policyService;

	@GetMapping
	@Operation(summary = "#504 查询命令权限视图（仅主人；默认规则+覆盖行+主人视角有效态）")
	public R<AiclawCommandPolicyResp> read(
			@RequestParam Long aiclawUid,
			@RequestParam(required = false) Long roomId) {
		return R.success(policyService.readForOwner(ContextUtil.getUid(), aiclawUid, roomId));
	}

	@PutMapping
	@Operation(summary = "#504 更新命令权限（仅主人；显式行 insert 或版本 CAS update）")
	public R<Void> update(@RequestBody AiclawCommandPolicyUpdateReq request) {
		policyService.updateForOwner(ContextUtil.getUid(), request);
		return R.success();
	}
}
