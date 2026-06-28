package com.luohuo.flex.ws.service;

import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.model.entity.ws.CcBindResultDTO;
import com.luohuo.flex.model.entity.ws.CcLaunchResp;
import com.luohuo.flex.ws.websocket.SessionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REQ-010 S9 (#100): CcBindService 关联/超时/离线/错误映射。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CcBindServiceTest {

	@Mock private SessionManager sessionManager;
	@Mock private PushService pushService;

	@InjectMocks
	private CcBindService service;

	private static final Long AICLAW = 200L;
	private static final Long ROOM = 10L;

	@Test
	@DisplayName("本地无 aiclaw 会话 → 抛离线/超时（不 NPE，多 ws-node 降级）")
	void offline_throws() {
		when(sessionManager.getUserSessions(AICLAW)).thenReturn(Set.of());
		BizException ex = assertThrows(BizException.class,
				() -> service.ccBind(AICLAW, ROOM, 1, null));
		assertEquals("CC 助理节点离线或响应超时", ex.getMessage());
	}

	@Test
	@DisplayName("node 回执成功 → 映射为 CcLaunchResp")
	void success_completesFuture() {
		when(sessionManager.getUserSessions(AICLAW)).thenReturn(Set.of(mock(org.springframework.web.reactive.socket.WebSocketSession.class)));
		// 推送时异步回执：从 push 的另一线程按 requestId complete（requestId 由 service 内部生成，
		// 故经 service.complete 公共 API 唤醒 —— 此处用回调线程模拟 node 回执）
		doAnswer(inv -> {
			// 模拟 node 立即回执：解析出 requestId 并 complete
			Object data = ((com.luohuo.flex.model.entity.WsBaseResp<?>) inv.getArgument(0)).getData();
			String requestId = (String) data.getClass().getMethod("getRequestId").invoke(data);
			CompletableFuture.runAsync(() -> service.complete(CcBindResultDTO.builder()
					.requestId(requestId)
					.launchCommand("claude --resume xyz")
					.workspaceDir("/work/abc")
					.build()));
			return null;
		}).when(pushService).sendPushMsg(any(com.luohuo.flex.model.entity.WsBaseResp.class), anyLong(), anyLong());

		CcLaunchResp resp = service.ccBind(AICLAW, ROOM, 1, null);
		assertEquals("claude --resume xyz", resp.getLaunchCommand());
		assertEquals("/work/abc", resp.getWorkspaceDir());
	}

	@Test
	@DisplayName("node 回执带 error → 抛节点生成失败")
	void nodeError_throws() {
		when(sessionManager.getUserSessions(AICLAW)).thenReturn(Set.of(mock(org.springframework.web.reactive.socket.WebSocketSession.class)));
		doAnswer(inv -> {
			Object data = ((com.luohuo.flex.model.entity.WsBaseResp<?>) inv.getArgument(0)).getData();
			String requestId = (String) data.getClass().getMethod("getRequestId").invoke(data);
			CompletableFuture.runAsync(() -> service.complete(CcBindResultDTO.builder()
					.requestId(requestId)
					.error("workspace not found")
					.build()));
			return null;
		}).when(pushService).sendPushMsg(any(com.luohuo.flex.model.entity.WsBaseResp.class), anyLong(), anyLong());

		BizException ex = assertThrows(BizException.class, () -> service.ccBind(AICLAW, ROOM, 1, null));
		assertEquals("节点生成失败", ex.getMessage());
	}
}
