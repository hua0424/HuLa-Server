package com.luohuo.flex.im.controller.chat;

import com.luohuo.basic.base.R;
import com.luohuo.flex.im.controller.ThinkingInternalAuth;
import com.luohuo.flex.im.core.chat.service.AgentCommandService;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AgentCommand;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/aiclaw/commands")
@RequiredArgsConstructor
public class AgentCommandController {
    private final AgentCommandService commands;
    private final ThinkingInternalAuth agentAuth;

    @PostMapping
    public R<AgentCommand.Result> submit(@RequestBody AgentCommand.Request request) {
        return R.success(commands.submit(request));
    }

    @GetMapping("/{requestId}")
    public R<AgentCommand.Result> get(@PathVariable String requestId) {
        return R.success(commands.get(requestId));
    }

    /** #505: 主人待审批列表；tenant 与 owner 由当前登录身份绑定。 */
    @GetMapping("/approvals")
    public R<List<AgentCommand.Approval>> approvals() {
        return R.success(commands.approvals());
    }

    /** #505: 空 body 批准冻结请求；只能由该 Agent 主人调用，重复批准幂等。 */
    @PostMapping("/{requestId}/approve")
    public R<AgentCommand.Result> approve(@PathVariable String requestId) {
        return R.success(commands.approve(requestId));
    }

    /** #505: 空 body 拒绝；不执行，立即解锁发起人当前输入框。 */
    @PostMapping("/{requestId}/reject")
    public R<AgentCommand.Result> reject(@PathVariable String requestId) {
        return R.success(commands.reject(requestId));
    }

    @PostMapping("/result")
    public R<AgentCommand.Result> result(@RequestBody AgentCommand.Callback result, HttpServletRequest request) {
        agentAuth.require(request);
        return R.success(commands.complete(result));
    }
}
