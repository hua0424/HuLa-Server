package com.luohuo.flex.im.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luohuo.flex.im.domain.entity.msg.MessageExtra;
import com.luohuo.flex.im.domain.vo.response.msg.BaseFileDTO;
import com.luohuo.flex.im.domain.vo.response.msg.FileMsgDTO;
import com.luohuo.flex.im.domain.vo.response.msg.ImgMsgDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * REQ-007 #72: server-side aiclaw 媒体转发体携带 url + mime + size + fileName。
 *
 * <p>转发链路把 {@code AbstractMsgHandler.showMsg} 返回的 {@code ImgMsgDTO}/{@code FileMsgDTO}
 * 放进 {@code ChatMessageResp.Message.body}；该 DTO 随 {@code MessageExtra} 一起被
 * Jackson 序列化用于持久化（{@code im_message.extra} 列走 {@code JacksonTypeHandler}）
 * 并在 WS 推送时再次 Jackson 序列化。所以「能否穿过持久化 + 转发」== 能否穿过
 * Jackson 往返。本测试用标准 {@code ObjectMapper}（与 {@code JacksonTypeHandler} 同款）
 * 证明新增的 {@code mime}（基类）/ 图片的 {@code fileName} 在往返后保留，且原有
 * url/size/尺寸不被破坏。
 *
 * <p>RED 判定：未加字段前，{@code ImgMsgDTO.getMime()/getFileName()} 不存在，本类无法编译。
 */
class MediaMsgForwardDtoTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	@DisplayName("#72: 图片转发体经 Jackson 往返后保留 url+mime+size+fileName+尺寸")
	void imgMsgDtoSurvivesJacksonRoundTripWithMimeAndFileName() throws Exception {
		ImgMsgDTO img = ImgMsgDTO.builder()
				.url("http://minio/tmp/chat/1_a.png?X-Amz-Signature=abc")
				.size(123L)
				.mime("image/png")
				.fileName("a.png")
				.width(10)
				.height(20)
				.build();
		MessageExtra extra = MessageExtra.builder().imgMsgDTO(img).build();

		String json = mapper.writeValueAsString(extra);
		MessageExtra back = mapper.readValue(json, MessageExtra.class);

		ImgMsgDTO got = back.getImgMsgDTO();
		assertEquals("http://minio/tmp/chat/1_a.png?X-Amz-Signature=abc", got.getUrl(), "url 应保留（含预签名 query）");
		assertEquals("image/png", got.getMime(), "mime 应穿过持久化+转发往返");
		assertEquals("a.png", got.getFileName(), "图片 fileName 应穿过往返");
		assertEquals(123L, got.getSize(), "size 应保留");
		assertEquals(10, got.getWidth(), "width 应保留");
		assertEquals(20, got.getHeight(), "height 应保留");
	}

	@Test
	@DisplayName("#72: 文件转发体经 Jackson 往返后保留 url+mime+size+fileName")
	void fileMsgDtoSurvivesJacksonRoundTripWithMime() throws Exception {
		FileMsgDTO file = FileMsgDTO.builder()
				.url("http://minio/tmp/chat/2_d.pdf?X-Amz-Signature=def")
				.size(456L)
				.mime("application/pdf")
				.fileName("d.pdf")
				.build();
		MessageExtra extra = MessageExtra.builder().fileMsg(file).build();

		String json = mapper.writeValueAsString(extra);
		MessageExtra back = mapper.readValue(json, MessageExtra.class);

		FileMsgDTO got = back.getFileMsg();
		assertEquals("http://minio/tmp/chat/2_d.pdf?X-Amz-Signature=def", got.getUrl(), "url 应保留（含预签名 query）");
		assertEquals("application/pdf", got.getMime(), "mime 应穿过持久化+转发往返");
		assertEquals("d.pdf", got.getFileName(), "fileName 应保留");
		assertEquals(456L, got.getSize(), "size 应保留");
	}

	@Test
	@DisplayName("#72: BaseFileDTO 直接暴露 getMime()（覆盖 Img/File/Sound/Video 共享基字段）")
	void baseFileDtoExposesMime() {
		BaseFileDTO base = ImgMsgDTO.builder()
				.url("http://minio/tmp/chat/3_x.webp")
				.size(7L)
				.mime("image/webp")
				.width(1)
				.height(1)
				.build();
		assertEquals("image/webp", base.getMime(), "基类 getMime() 应返回设置值");
	}

	@Test
	@DisplayName("#72: 图片可省略 mime（nullable，不应被校验/序列化为非空要求）")
	void imgMimeMayBeNull() throws Exception {
		ImgMsgDTO img = ImgMsgDTO.builder()
				.url("http://minio/tmp/chat/4_n.png")
				.size(9L)
				.width(2)
				.height(3)
				.build();
		MessageExtra back = mapper.readValue(
				mapper.writeValueAsString(MessageExtra.builder().imgMsgDTO(img).build()),
				MessageExtra.class);
		assertEquals(null, back.getImgMsgDTO().getMime(), "未设 mime 时往返后应为 null");
		assertEquals(null, back.getImgMsgDTO().getFileName(), "未设 fileName 时往返后应为 null");
	}
}
