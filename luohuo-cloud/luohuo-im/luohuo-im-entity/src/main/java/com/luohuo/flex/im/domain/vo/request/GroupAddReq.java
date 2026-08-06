package com.luohuo.flex.im.domain.vo.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 新建群组
 * @author nyh
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GroupAddReq {
    @NotNull
    @Size(min = 1, max = 50,message = "只能邀请1-50人")
    @Schema(description ="邀请的uid")
    private List<Long> uidList;

    // #202: 群名上限统一 32（im_room_group.name 历史 varchar(16) 无校验 → INSERT 触发 SQL_EX(-4)）；
    // 不加 min/NotNull——null/空串走默认群名生成路径
    @Size(max = 32, message = "群名称最长32个字符")
    @Schema(description ="群聊名称")
    private String groupName;
}
