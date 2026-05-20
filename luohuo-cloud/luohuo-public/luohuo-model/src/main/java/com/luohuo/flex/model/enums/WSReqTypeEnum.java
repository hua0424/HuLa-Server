package com.luohuo.flex.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 *  ws前端请求类型枚举
 * @author nyh
 */
@AllArgsConstructor
@Getter
public enum WSReqTypeEnum {
    LOGIN(1, "请求登录二维码"),
    HEARTBEAT(2, "心跳包"),
    AUTHORIZE(3, "登录认证"),

	VIDEO_HEARTBEAT(4,"视频心跳"),
	VIDEO_CALL_REQUEST(5,"视频通话请求"),
	VIDEO_CALL_RESPONSE(6,"视频通话响应"),
	MEDIA_MUTE_AUDIO(7,"媒体静音音频"),
	MEDIA_MUTE_VIDEO(8,"静音视频"),
	MEDIA_MUTE_ALL(9,"静音全部用户"),
	SCREEN_SHARING(10,"屏幕共享"),
	CLOSE_ROOM(11,"关闭房间"),
	KICK_USER(12,"踢出用户"),
	NETWORK_REPORT(13,"通话质量监控"),
	WEBRTC_SIGNAL(14,"信令消息"),
	ACK(15, "消息确认接收ack"),
	READ(16, "消息已读"),
	STREAM_START(17, "流式消息开始"),
	STREAM_DELTA(18, "流式消息片段"),
	STREAM_END(19, "流式消息结束"),

	// REQ-004: thinking 事件
	THINKING_START(20, "thinking 开始"),
	THINKING_DELTA(21, "thinking 增量"),
	THINKING_END(22, "thinking 结束"),
    ;

    private final Integer type;
    private final String desc;

    private static Map<Integer, WSReqTypeEnum> cache;

    static {
        cache = Arrays.stream(WSReqTypeEnum.values()).collect(Collectors.toMap(WSReqTypeEnum::getType, Function.identity()));
    }

    public static WSReqTypeEnum of(Integer type) {
        return cache.get(type);
    }

	public boolean eq(Integer type) {
		return equals(cache.get(type));
	}
}
