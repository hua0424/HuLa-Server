package com.luohuo.flex.im.domain.vo.resp.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * aichatoverview#504 命令权限视图（仅主人可见）。
 *
 * <p>defaults = 代码常量默认（未配置=继承）；rows = 显式覆盖行（含乐观锁版本）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "aichatoverview#504 命令权限视图")
public class AiclawCommandPolicyResp {

	@Schema(description = "默认规则（代码常量，能力 id -> open/closed）")
	private List<Row> defaults;

	@Schema(description = "显式覆盖行（默认规则行 + 群覆盖行，含版本）")
	private List<Row> rows;

	@Schema(description = "请求作用域下主人视角有效态（capabilityId -> open/closed；主人自批已折入）")
	private Map<String, String> effective;

	@Data
	@Builder
	@AllArgsConstructor
	@NoArgsConstructor
	@Schema(description = "aichatoverview#504 单能力权限行")
	public static class Row {

		@Schema(description = "群 room_id；0=默认规则；>0=群覆盖")
		private Long roomId;

		@Schema(description = "能力标识（精确 id）")
		private String capabilityId;

		@Schema(description = "open / closed / approval_required（defaults 行仅 open/closed）")
		private String state;

		@Schema(description = "群覆盖名单模式 all / list；默认规则行忽略")
		private String mode;

		@Schema(description = "mode=list 时的 uid 数组")
		private List<Long> allowedUids;

		@Schema(description = "覆盖行乐观锁版本；defaults 行无版本")
		private Integer version;
	}
}
