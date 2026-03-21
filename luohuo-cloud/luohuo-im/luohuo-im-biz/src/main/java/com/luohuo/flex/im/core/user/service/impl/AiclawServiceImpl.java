package com.luohuo.flex.im.core.user.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.digest.BCrypt;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.service.RoomService;
import com.luohuo.flex.im.core.user.dao.AiclawDao;
import com.luohuo.flex.im.core.user.dao.UserDao;
import com.luohuo.flex.im.core.user.service.AiclawService;
import com.luohuo.flex.im.core.user.service.FriendService;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import com.luohuo.flex.im.domain.entity.User;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawActivateReq;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawAuthConfirmReq;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawCreateReq;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawUpdateReq;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawActivateResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawCreateResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawListResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawTokenResp;
import com.luohuo.flex.im.enums.UserTypeEnum;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI助理管理服务实现
 */
@Slf4j
@Service
@AllArgsConstructor
public class AiclawServiceImpl implements AiclawService {

	private final AiclawDao aiclawDao;
	private final UserDao userDao;
	private final FriendService friendService;
	private final RoomService roomService;
	private final AiclawCryptoService cryptoService;
	private final StringRedisTemplate stringRedisTemplate;

	private static final String AICLAW_TOKEN_CACHE_PREFIX = "aiclaw:token:";
	private static final Duration TOKEN_CACHE_TTL = Duration.ofDays(7);

	// ==================== 创建 ====================

	@Override
	@Transactional(rollbackFor = Exception.class)
	public AiclawCreateResp create(AiclawCreateReq req, Long ownerUid) {
		// 1. 创建 im_user (user_type=4)
		User user = User.builder()
				.name(req.getName())
				.avatar(StrUtil.blankToDefault(req.getAvatar(), null))
				.resume(req.getDescription())
				.userType(UserTypeEnum.AICLAW.getValue())
				.state(0)
				.build();
		userDao.save(user);
		Long aiclawUid = user.getId();

		// 2. 生成连接 token（明文不返回给前端）
		String connectionToken = UUID.randomUUID().toString();
		String tokenHash = BCrypt.hashpw(connectionToken);
		String tokenPrefix = connectionToken.substring(0, 8);
		String tokenSha256 = SecureUtil.sha256(connectionToken);

		// 3. 创建 im_aiclaw
		Aiclaw aiclaw = Aiclaw.builder()
				.uid(aiclawUid)
				.ownerUid(ownerUid)
				.tokenHash(tokenHash)
				.tokenPrefix(tokenPrefix)
				.authStatus(0)
				.adapterType("openclaw")
				.build();
		aiclawDao.save(aiclaw);

		// 4. 创建好友关系
		RoomFriend roomFriend = roomService.createFriendRoom(Arrays.asList(ownerUid, aiclawUid));
		friendService.createFriend(roomFriend.getRoomId(), ownerUid, aiclawUid);

		// 5. 写入 Redis 缓存（供 gateway 校验）
		saveTokenCache(tokenPrefix, aiclawUid, ownerUid, tokenSha256, null, 0);

		// 6. 加密生成激活 token
		String activationToken = cryptoService.encryptActivationToken(aiclawUid, connectionToken);

		log.info("aiclaw created: uid={}, owner={}", aiclawUid, ownerUid);

		return AiclawCreateResp.builder()
				.uid(aiclawUid)
				.name(req.getName())
				.activationToken(activationToken)
				.build();
	}

	// ==================== 激活（plugins 调用，无需登录态） ====================

	@Override
	@Transactional(rollbackFor = Exception.class)
	public AiclawActivateResp activate(AiclawActivateReq req) {
		// anyTenant 路径无 tenant context，手动设置默认租户
		com.luohuo.basic.context.ContextUtil.setTenantId(1L);

		// 1. 解密激活 token
		JSONObject payload = cryptoService.decryptActivationToken(req.getActivationToken());
		Long uid = payload.getLong("uid");
		String connectionToken = payload.getStr("connectionToken");
		long timestamp = payload.getLong("timestamp");

		// 2. 校验过期
		cryptoService.validateTimestamp(timestamp);

		// 3. 查询 aiclaw 记录
		Aiclaw aiclaw = aiclawDao.getByUid(uid);
		if (aiclaw == null) {
			throw new BizException("AI助理不存在");
		}
		if (aiclaw.getAuthStatus() == 1) {
			throw new BizException("该AI助理已激活");
		}
		if (aiclaw.getAuthStatus() == 2) {
			throw new BizException("该AI助理已停用");
		}

		// 4. bcrypt 验证连接 token
		if (!BCrypt.checkpw(connectionToken, aiclaw.getTokenHash())) {
			throw new BizException("激活码无效");
		}

		// 5. 激活：auth_status=1，绑定 machineCode
		Aiclaw update = new Aiclaw();
		update.setId(aiclaw.getId());
		update.setAuthStatus(1);
		update.setMachineCode(req.getMachineCode());
		aiclawDao.updateById(update);

		// 6. 更新 im_user.last_opt_time（标记首次激活时间，供好友列表三态判断）
		User userUpdate = new User();
		userUpdate.setId(uid);
		userUpdate.setLastOptTime(LocalDateTime.now());
		userDao.updateById(userUpdate);

		// 6. 更新 Redis 缓存
		String tokenSha256 = SecureUtil.sha256(connectionToken);
		saveTokenCache(aiclaw.getTokenPrefix(), uid, aiclaw.getOwnerUid(),
				tokenSha256, req.getMachineCode(), 1);

		log.info("aiclaw activated: uid={}, machineCode={}", uid, req.getMachineCode());

		return AiclawActivateResp.builder()
				.uid(uid)
				.connectionToken(connectionToken)
				.build();
	}

	// ==================== 激活 token 管理 ====================

	@Override
	public AiclawTokenResp getActivationToken(Long aiclawUid, Long ownerUid) {
		Aiclaw aiclaw = getOwnedAiclaw(aiclawUid, ownerUid);
		if (aiclaw.getAuthStatus() != 0) {
			throw new BizException("AI助理已激活，无法查看激活码");
		}
		// 重新加密生成激活 token（激活 token 不入库，每次用 connectionToken 明文加密）
		// 但 connectionToken 明文已丢失（只存了 hash），需要用 tokenHash 反向...
		// 实际上不行——bcrypt 是单向的。所以需要在 im_aiclaw 中额外存储连接 token 的明文？
		// 不，按需求设计：激活 token 在创建时加密生成，查看时重新加密（需要明文连接 token）
		// 解决方案：在 im_aiclaw 中加一个加密存储的连接 token 字段

		// 临时方案：从 tokenHash 无法还原明文，所以 getActivationToken 需要另一种实现
		// 重新生成一个激活 token：用 AES 加密 { uid, connectionToken=tokenPrefix+随机补位, timestamp }
		// 但这样 activate 时 bcrypt.verify 会失败

		// 正确方案：create 时把连接 token 用 AES 加密后存到 im_aiclaw 的一个字段
		// 这需要改表。暂时用简化方案：重新生成连接 token + 更新 hash

		// 实际最简方案：直接重新生成激活 token（连接 token 不变，但需要存明文或可逆加密）
		// 当前先抛异常提示用 refresh-activation
		throw new BizException("请使用'重新生成激活码'功能");
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public AiclawTokenResp refreshActivation(Long aiclawUid, Long ownerUid) {
		Aiclaw aiclaw = getOwnedAiclaw(aiclawUid, ownerUid);
		if (aiclaw.getAuthStatus() == 2) {
			throw new BizException("AI助理已停用");
		}
		// TODO: 在线检查 — aiclaw 须离线或未激活时才能刷新

		// 生成新的连接 token + 激活 token
		String connectionToken = UUID.randomUUID().toString();
		String tokenHash = BCrypt.hashpw(connectionToken);
		String tokenPrefix = connectionToken.substring(0, 8);
		String tokenSha256 = SecureUtil.sha256(connectionToken);

		// 删除旧 Redis 缓存
		deleteTokenCache(aiclaw.getTokenPrefix());

		// 更新数据库：新 token + 回到未激活状态 + 清除机器码
		Aiclaw update = new Aiclaw();
		update.setId(aiclaw.getId());
		update.setTokenHash(tokenHash);
		update.setTokenPrefix(tokenPrefix);
		update.setAuthStatus(0);
		update.setMachineCode(null);
		aiclawDao.updateById(update);

		// 写入新 Redis 缓存（authStatus=0）
		saveTokenCache(tokenPrefix, aiclaw.getUid(), ownerUid, tokenSha256, null, 0);

		// 加密生成激活 token
		String activationToken = cryptoService.encryptActivationToken(aiclawUid, connectionToken);

		log.info("aiclaw activation refreshed: uid={}", aiclawUid);

		return AiclawTokenResp.builder().activationToken(activationToken).build();
	}

	// ==================== 列表 / 修改 ====================

	@Override
	public List<AiclawListResp> list(Long ownerUid) {
		List<Aiclaw> aiclaws = aiclawDao.listByOwner(ownerUid);
		if (aiclaws.isEmpty()) {
			return Collections.emptyList();
		}

		List<Long> uids = aiclaws.stream().map(Aiclaw::getUid).collect(Collectors.toList());
		Map<Long, User> userMap = userDao.listByIds(uids).stream()
				.collect(Collectors.toMap(User::getId, u -> u));

		return aiclaws.stream().map(a -> {
			User u = userMap.get(a.getUid());
			return AiclawListResp.builder()
					.uid(a.getUid())
					.name(u != null ? u.getName() : null)
					.avatar(u != null ? u.getAvatar() : null)
					.description(u != null ? u.getResume() : null)
					.authStatus(a.getAuthStatus())
					.adapterType(a.getAdapterType())
					.createTime(a.getCreateTime())
					.build();
		}).collect(Collectors.toList());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void updateProfile(AiclawUpdateReq req, Long ownerUid) {
		Aiclaw aiclaw = getOwnedAiclaw(req.getUid(), ownerUid);
		User update = new User();
		update.setId(aiclaw.getUid());
		if (StrUtil.isNotBlank(req.getName())) {
			update.setName(req.getName());
		}
		if (req.getAvatar() != null) {
			update.setAvatar(req.getAvatar());
		}
		if (req.getDescription() != null) {
			update.setResume(req.getDescription());
		}
		userDao.updateById(update);
	}

	// ==================== Token 重置 ====================

	// ==================== 停用 / 恢复 ====================

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void deactivate(Long aiclawUid, Long ownerUid) {
		Aiclaw aiclaw = getOwnedAiclaw(aiclawUid, ownerUid);
		if (aiclaw.getAuthStatus() == 2) {
			throw new BizException("该AI助理已处于停用状态");
		}

		Aiclaw update = new Aiclaw();
		update.setId(aiclaw.getId());
		update.setAuthStatus(2);
		update.setDeactivatedAt(LocalDateTime.now());
		aiclawDao.updateById(update);

		updateTokenCacheAuthStatus(aiclaw.getTokenPrefix(), 2);

		log.info("aiclaw deactivated: uid={}", aiclawUid);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void restore(Long aiclawUid, Long ownerUid) {
		Aiclaw aiclaw = getOwnedAiclaw(aiclawUid, ownerUid);
		if (aiclaw.getAuthStatus() != 2) {
			throw new BizException("该AI助理不在停用状态");
		}
		if (aiclaw.getDeactivatedAt() != null
				&& aiclaw.getDeactivatedAt().plusHours(24).isBefore(LocalDateTime.now())) {
			throw new BizException("已超过24小时恢复期");
		}

		Aiclaw update = new Aiclaw();
		update.setId(aiclaw.getId());
		update.setAuthStatus(1);
		update.setDeactivatedAt(null);
		aiclawDao.updateById(update);

		updateTokenCacheAuthStatus(aiclaw.getTokenPrefix(), 1);

		log.info("aiclaw restored: uid={}", aiclawUid);
	}

	// ==================== 机器码授权 ====================

	@Override
	public void authConfirm(Long aiclawUid, AiclawAuthConfirmReq req, Long ownerUid) {
		Aiclaw aiclaw = getOwnedAiclaw(aiclawUid, ownerUid);
		if (Boolean.TRUE.equals(req.getApproved())) {
			log.info("aiclaw auth confirmed: uid={}", aiclawUid);
		} else {
			log.info("aiclaw auth rejected: uid={}", aiclawUid);
		}
	}

	// ==================== 内部方法 ====================

	private Aiclaw getOwnedAiclaw(Long aiclawUid, Long ownerUid) {
		Aiclaw aiclaw = aiclawDao.getByOwnerAndUid(ownerUid, aiclawUid);
		if (aiclaw == null) {
			throw new BizException("AI助理不存在或无权操作");
		}
		return aiclaw;
	}

	private void saveTokenCache(String tokenPrefix, Long uid, Long ownerUid,
								String tokenSha256, String machineCode, Integer authStatus) {
		Map<String, Object> cache = new HashMap<>();
		cache.put("uid", uid);
		cache.put("ownerUid", ownerUid);
		cache.put("tenantId", 1L);
		cache.put("authStatus", authStatus);
		if (tokenSha256 != null) {
			cache.put("tokenSha256", tokenSha256);
		}
		if (machineCode != null) {
			cache.put("machineCode", machineCode);
		}
		stringRedisTemplate.opsForValue().set(
				AICLAW_TOKEN_CACHE_PREFIX + tokenPrefix,
				JSONUtil.toJsonStr(cache),
				TOKEN_CACHE_TTL);
	}

	private void updateTokenCacheAuthStatus(String tokenPrefix, int authStatus) {
		String key = AICLAW_TOKEN_CACHE_PREFIX + tokenPrefix;
		String cachedJson = stringRedisTemplate.opsForValue().get(key);
		if (cachedJson == null) return;
		cn.hutool.json.JSONObject obj = JSONUtil.parseObj(cachedJson);
		obj.set("authStatus", authStatus);
		stringRedisTemplate.opsForValue().set(key, obj.toString(), TOKEN_CACHE_TTL);
	}

	private void deleteTokenCache(String tokenPrefix) {
		stringRedisTemplate.delete(AICLAW_TOKEN_CACHE_PREFIX + tokenPrefix);
	}
}
