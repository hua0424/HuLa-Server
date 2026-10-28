package com.luohuo.flex.im.controller.chat.view;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.luohuo.basic.base.R;
import com.luohuo.flex.im.core.user.service.dto.PushSubmission;

/**
 * #482 局部响应视图：给首批带提示的接口附加顶层 {@code notificationSync}。
 * <p>
 * 只在「业务已成功 且 必要通知在请求结束前未全部接收」时出现该字段；其余字段（success/code/data/msg/path/version/timestamp）
 * 与 {@link R#success(Object)} 完全一致，绝不复写全局 {@code R<T>}。字段缺省表示「没有补充信息」，
 * 绝不代表已送达；即使全部接收也不会返回 COMPLETE/DELIVERED。
 *
 * @author 乾乾
 */
public class NotificationSyncResponse<T> extends R<T> {

	/** 同步补充状态；当前只有 INCOMPLETE，未知未来状态不会在此臆造成功或失败。 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final NotificationSync notificationSync;

	private NotificationSyncResponse(T data, NotificationSync notificationSync) {
		super(R.SUCCESS_CODE, data, "");
		this.notificationSync = notificationSync;
	}

	/**
	 * 业务成功后的响应构造：通知未全部接收时附加 INCOMPLETE，否则与普通成功响应一致。
	 *
	 * @param data       原接口 data（管理员接口保持 null）
	 * @param submission 本次业务提交的通知回执，可为 null（未发生通知）
	 */
	public static <T> R<T> of(T data, PushSubmission submission) {
		if (submission == null || !submission.isSyncIncomplete()) {
			return R.success(data);
		}
		return new NotificationSyncResponse<>(data, new NotificationSync("INCOMPLETE"));
	}

	public NotificationSync getNotificationSync() {
		return notificationSync;
	}

	/** 顶层 {@code notificationSync} 载体。 */
	public static final class NotificationSync {

		private final String state;

		private NotificationSync(String state) {
			this.state = state;
		}

		public String getState() {
			return state;
		}
	}
}
