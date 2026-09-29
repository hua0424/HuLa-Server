package com.luohuo.flex.im.controller.user;

import io.swagger.v3.oas.annotations.Operation;
import com.luohuo.basic.base.R;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.im.domain.vo.req.CursorPageBaseReq;
import com.luohuo.flex.im.domain.vo.res.CursorPageBaseResp;
import com.luohuo.flex.im.domain.vo.request.friend.FriendPermissionReq;
import com.luohuo.flex.im.domain.vo.request.friend.FriendRemarkReq;
import com.luohuo.flex.im.domain.vo.response.ChatMemberListResp;
import com.luohuo.flex.im.domain.vo.req.friend.FriendCheckReq;
import com.luohuo.flex.im.domain.vo.req.friend.FriendDeleteReq;
import com.luohuo.flex.im.domain.vo.req.friend.FriendReq;
import com.luohuo.flex.im.domain.vo.resp.friend.FriendCheckResp;
import com.luohuo.flex.im.domain.vo.resp.friend.FriendResp;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.user.service.FriendService;
import com.luohuo.flex.im.core.user.service.RoleService;
import com.luohuo.flex.im.domain.enums.RoleTypeEnum;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;


/**
 * 好友相关接口
 * @author 乾乾
 */
@RestController
@RequestMapping("/user/friend")
@Tag(name = "好友相关接口")
@Slf4j
public class FriendController {
    @Resource
    private FriendService friendService;

    @Resource
    private RoleService roleService;

    @GetMapping("/check")
    @Operation(summary = "批量判断是否是自己好友")
    public R<FriendCheckResp> check(@Valid FriendCheckReq request) {
        Long uid = ContextUtil.getUid();
        return R.success(friendService.check(uid, request));
    }

	@GetMapping("search")
	@Operation(summary = "查找联系人")
	public R<List<ChatMemberListResp>> search(@Valid FriendReq friendReq) {
		return R.success(friendService.searchFriend(friendReq));
	}

	@DeleteMapping
    @Operation(summary = "删除好友")
    public R<Boolean> delete(@Valid @RequestBody FriendDeleteReq request) {
        Long uid = ContextUtil.getUid();
        friendService.deleteFriend(uid, request.getTargetUid());
        return R.success();
    }

    @GetMapping("/page")
    @Operation(summary = "联系人列表")
    public R<CursorPageBaseResp<FriendResp>> friendList(@Valid CursorPageBaseReq request, @RequestParam(required = false) Long uid) {
        // #296: 私密关系数据只按当前认证身份可见。显式 uid 仅限管理员（后台场景）查询他人；
        // 普通用户/aiclaw 传入他人 uid 一律拒绝，防止 aiclaw 借 uid 读 owner 的好友/备注。
        Long caller = ContextUtil.getUid();
        Long targetUid = uid != null ? uid : caller;
        if (!targetUid.equals(caller) && !roleService.hasRole(caller, RoleTypeEnum.ADMIN)) {
            throw new BizException("无权查询他人好友列表");
        }
        return R.success(friendService.friendList(targetUid, request));
    }

	@PostMapping("/updateRemark")
	@Operation(summary = "修改好友备注")
	public R<Boolean> updateRemark(@Valid @RequestBody FriendRemarkReq request) {
		return R.success(friendService.updateRemark(ContextUtil.getUid(), request));
	}

	@PostMapping("/permissionSettings")
	@Operation(summary = "好友权限设置")
	public R<Boolean> permissionSettings(@RequestBody FriendPermissionReq request) {
		return R.success(friendService.permissionSettings(ContextUtil.getUid(), request));
	}
}

