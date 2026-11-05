package com.luohuo.flex.im.controller.chat;

import com.luohuo.basic.base.R;
import com.luohuo.flex.im.controller.ThinkingInternalAuth;
import com.luohuo.flex.im.core.chat.service.AgentCommandService;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AgentCommand;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/result")
    public R<AgentCommand.Result> result(@RequestBody AgentCommand.Callback result, HttpServletRequest request) {
        agentAuth.require(request);
        return R.success(commands.complete(result));
    }
}
