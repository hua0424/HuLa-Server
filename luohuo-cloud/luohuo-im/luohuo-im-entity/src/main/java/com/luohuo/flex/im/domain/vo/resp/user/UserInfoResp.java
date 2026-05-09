package com.luohuo.flex.im.domain.vo.resp.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @author nyh
 */
@Data
public class UserInfoResp implements Serializable {

    @Schema(description = "用户id")
    private Long uid;

	@Schema(description = "佩戴的徽章id")
	private Long wearingItemId;

	@Schema(description = "用户拥有的徽章id列表")
	private List<Long> itemIds;

    @Schema(description = "Hula号")
    private String account;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "用户昵称")
    private String name;

    @Schema(description = "头像")
    private String avatar;

    @Schema(description = "性别 1男 2女")
    private Integer sex;

	@Schema(description = "个人简介")
	private String resume;

    @Schema(description = "用户状态id")
    private Long userStateId;

    @Schema(description = "修改昵称次数")
    private Integer modifyNameChance;

    @Schema(description = "头像更换时间")
    private LocalDateTime avatarUpdateTime;

	@Schema(description = "是否开启上下文[AI模块]")
	private Boolean context;

    @Schema(description = "调用次数[AI模块]")
    private Integer num;

    @Schema(description = "是否绑定Gitee")
    private Boolean linkedGitee;

    @Schema(description = "是否绑定Github")
    private Boolean linkedGithub;

    @Schema(description = "是否绑定GitCode")
    private Boolean linkedGitcode;

    @Schema(description = "用户类型 1系统 2机器人 3普通 4AI助理")
    private Integer userType;

    @Schema(description = "AI助理的 owner 信息，仅 userType=4 时返回")
    private OwnerInfo ownerInfo;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class OwnerInfo implements Serializable {
        @Schema(description = "owner 用户ID")
        private Long uid;
        @Schema(description = "owner 昵称")
        private String name;
        @Schema(description = "owner 头像")
        private String avatar;
    }
}
