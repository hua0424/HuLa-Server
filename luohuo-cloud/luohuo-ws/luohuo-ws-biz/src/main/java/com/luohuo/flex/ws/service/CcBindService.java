package com.luohuo.flex.ws.service;

import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.model.entity.WSRespTypeEnum;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.CcBindRequestDTO;
import com.luohuo.flex.model.entity.ws.CcBindResultDTO;
import com.luohuo.flex.model.entity.ws.CcLaunchResp;
import com.luohuo.flex.ws.websocket.SessionManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * REQ-010 S9: CC（claude-code）绑定服务。
 *
 * <p>流程：生成 requestId → 注册待完成 future → 经 ws 会话把 {@code ccBindRequest}
 * 推送给目标 CC aiclaw 的 node → node 回执 {@code CC_BIND_RESULT} 由
 * {@code CcBindResultProcessor} 按 requestId {@link #complete} 唤醒本 future →
 * 映射为 {@link CcLaunchResp} 返回。超时/离线/node 报错均抛 {@link BizException}。</p>
 *
 * <p><b>单 ws-node 假设（测试环境）</b>：requestId→future 的关联是<em>进程内</em>的，
 * 仅当目标 aiclaw 的会话就在本 ws-node 上才成立。多 ws-node 是 BL-016（超范围）——
 * 见 {@link #ccBind} 中对 {@code SessionManager} 本地会话缺失的优雅降级（返回离线，不 NPE）。</p>
 *
 * @author developer
 */
@Slf4j
@Service
public class CcBindService {

	/** 等待 node 回执的超时（毫秒）。 */
	private static final long BIND_TIMEOUT_MS = 10_000L;

	@Resource
	private SessionManager sessionManager;
	@Resource
	private PushService pushService;

	/** requestId → 待 node 回执的 future（进程内关联）。 */
	private final ConcurrentHashMap<String, CompletableFuture<CcBindResultDTO>> pending = new ConcurrentHashMap<>();

	/**
	 * 向目标 CC aiclaw 的 node 请求 owner 启动命令并同步等待结果。
	 *
	 * @param aiclawUid      目标 CC aiclaw uid（已由 im 侧解析 + owner 鉴权通过）
	 * @param roomId         房间 id
	 * @param roomType       房间类型 1=群聊 2=单聊
	 * @param counterpartUid 单聊对端 uid；群聊为 null
	 * @return owner 启动命令 + 工作区目录
	 * @throws BizException 节点离线 / 响应超时 / 节点生成失败
	 */
	public CcLaunchResp ccBind(Long aiclawUid, Long roomId, Integer roomType, Long counterpartUid) {
		// 1. 本地会话存在性检查：进程内关联只对本 ws-node 上的会话有效。
		//    多 ws-node（BL-016）下目标会话可能落在别的节点 → 此处查不到 → 优雅按"离线"处理，不 NPE。
		if (sessionManager.getUserSessions(aiclawUid).isEmpty()) {
			throw new BizException("CC 助理节点离线或响应超时");
		}

		String requestId = UUID.randomUUID().toString();
		CompletableFuture<CcBindResultDTO> future = new CompletableFuture<>();
		pending.put(requestId, future);

		try {
			// 2. 经 ws 会话把绑定请求推给 aiclaw 的 node（precise push：仅该 uid）
			CcBindRequestDTO data = CcBindRequestDTO.builder()
					.roomId(roomId)
					.roomType(roomType)
					.counterpartUid(counterpartUid)
					.requestId(requestId)
					.build();
			WsBaseResp<CcBindRequestDTO> resp = new WsBaseResp<>();
			resp.setType(WSRespTypeEnum.CC_BIND_REQUEST.getType());
			resp.setData(data);
			pushService.sendPushMsg(resp, aiclawUid, aiclawUid);

			// 3. 同步等待 node 回执（带超时）
			CcBindResultDTO result;
			try {
				result = future.get(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				log.warn("ccBind timeout: aiclawUid={}, roomId={}, requestId={}", aiclawUid, roomId, requestId);
				throw new BizException("CC 助理节点离线或响应超时");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new BizException("CC 助理节点离线或响应超时");
			} catch (Exception e) {
				log.error("ccBind await failed: aiclawUid={}, requestId={}", aiclawUid, requestId, e);
				throw new BizException("CC 助理节点离线或响应超时");
			}

			// 4. 映射结果
			if (result.getError() != null && !result.getError().isBlank()) {
				log.warn("ccBind node error: aiclawUid={}, requestId={}, error={}", aiclawUid, requestId, result.getError());
				throw new BizException("节点生成失败");
			}
			if (result.getLaunchCommand() == null || result.getLaunchCommand().isBlank()) {
				log.warn("ccBind node returned empty launchCommand: aiclawUid={}, requestId={}", aiclawUid, requestId);
				throw new BizException("节点生成失败");
			}
			return CcLaunchResp.builder()
					.launchCommand(result.getLaunchCommand())
					.workspaceDir(result.getWorkspaceDir())
					.build();
		} finally {
			// 5. 始终清理，避免内存泄漏（含超时/异常路径）
			pending.remove(requestId);
		}
	}

	/**
	 * 由 {@code CcBindResultProcessor} 在收到 node 回执时调用，按 requestId 完成对应 future。
	 *
	 * @param result node 回执载荷（含 requestId）
	 */
	public void complete(CcBindResultDTO result) {
		if (result == null || result.getRequestId() == null) {
			log.warn("ccBind complete: null result or requestId");
			return;
		}
		CompletableFuture<CcBindResultDTO> future = pending.get(result.getRequestId());
		if (future == null) {
			// 已超时清理 / 跨节点回执 / 重复回执：无对应 future，安全忽略
			log.warn("ccBind complete: no pending request for requestId={}", result.getRequestId());
			return;
		}
		future.complete(result);
	}
}
