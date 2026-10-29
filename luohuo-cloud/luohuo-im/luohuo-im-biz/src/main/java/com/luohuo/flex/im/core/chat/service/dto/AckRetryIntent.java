package com.luohuo.flex.im.core.chat.service.dto;

import com.luohuo.flex.model.entity.WsBaseResp;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * #482：ACK 持久重放的逐设备快照。
 * <p>
 * 该对象会被整体序列化进 {@code secure_invoke_record.secure_invoke_json.args}，
 * 由 {@code SecureInvokeService.doInvoke} 通过 {@code Class.forName(参数类型全限定名)} +
 * {@code JsonUtil.nodeToValue} 反序列化后反射调用。
 * <p>
 * 因此：类名、包名、字段名与类型都属于「持久化契约」——改名/改包/增删必填字段等同于兼容性变更，
 * 必须保证旧记录仍可反序列化（新增字段一律给默认值容错，不设基本类型必填）。
 */
@Data
@NoArgsConstructor
public class AckRetryIntent implements Serializable {

	/** 提交时捕获的可信租户；重放前用它恢复上下文，绝不在工作线程猜默认租户。 */
	private Long tenantId;
	/** 原始操作人 uid（发送方），仅用于语义还原与日志，不参与目标扩张。 */
	private Long actorUid;
	/** 未收到 ACK 的原消息 id（在途集合成员）。 */
	private Long hashId;
	/** 需要重推的唯一目标用户。 */
	private Long recipientUid;
	/** 需要重推的唯一设备指纹；重放不得扩展到同一 uid 的其他设备。 */
	private String deviceId;
	/**
	 * 原始 ws 载荷，重放时原样投回。
	 * 与 NodePushDTO 一致用通配类型：反序列化后 data 为 Map 结构，不做二次改造。
	 */
	private WsBaseResp<?> payload;

	public AckRetryIntent(Long tenantId, Long actorUid, Long hashId, Long recipientUid, String deviceId) {
		this.tenantId = tenantId;
		this.actorUid = actorUid;
		this.hashId = hashId;
		this.recipientUid = recipientUid;
		this.deviceId = deviceId;
	}
}
