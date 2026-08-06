package com.luohuo.flex.im.domain.vo.request.friend;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Description: 修改好友备注
 * Date: 2025-02-19
 */
@Data
public class FriendRemarkReq {
	public FriendRemarkReq() {
	}

	public FriendRemarkReq(Long targetUid, String remark) {
		this.targetUid = targetUid;
		this.remark = remark;
	}

    @NotNull(message = "请选择好友")
    @Schema(description = "好友uid")
    private Long targetUid;

	@Size(min = 0, max = 10, message = "好友备注长度必须在0到10个字符之间")
	@Schema(description = "好友备注（空串/null = 清空备注，恢复显昵称）")
	private String remark;
}
