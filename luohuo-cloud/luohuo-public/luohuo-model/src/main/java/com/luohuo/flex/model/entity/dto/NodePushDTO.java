package com.luohuo.flex.model.entity.dto;

import com.luohuo.flex.model.entity.WsBaseResp;
import jakarta.annotation.Nonnull;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 指纹级别的精确路由
 * @author 乾乾
 */
@Data
@NoArgsConstructor
public class NodePushDTO implements Serializable {
    /**
     * 推送的ws消息
     */
    private WsBaseResp<?> wsBaseMsg;
    /**
     * 指纹与uid的映射
     */
	private Map<String, Long> deviceUserMap;
	/**
	 * 消息唯一hashId
	 */
	private Long hashId;
    /**
     * 操作人uid
     */
    private Long uid;
	/**
	 * #482：提交时捕获的可信租户，供 ACK 持久重放恢复上下文。
	 * 可选字段：老消息、老构造器不带此值时为 null，消费方必须容忍。
	 */
	private Long tenantId;

	public NodePushDTO(WsBaseResp<?> wsBaseMsg, Map<String, Long> deviceUserMap, @Nonnull Long hashId, @Nonnull Long uid) {
		this.wsBaseMsg = wsBaseMsg;
		this.deviceUserMap = deviceUserMap;
		this.hashId = hashId;
		this.uid = uid;
	}

	/**
	 * #482：新增 ACK 探针生产者必须在同步入口捕获租户后传进来，禁止在工作线程猜测默认租户。
	 */
	public NodePushDTO(WsBaseResp<?> wsBaseMsg, Map<String, Long> deviceUserMap, @Nonnull Long hashId, @Nonnull Long uid, Long tenantId) {
		this(wsBaseMsg, deviceUserMap, hashId, uid);
		this.tenantId = tenantId;
	}
}
