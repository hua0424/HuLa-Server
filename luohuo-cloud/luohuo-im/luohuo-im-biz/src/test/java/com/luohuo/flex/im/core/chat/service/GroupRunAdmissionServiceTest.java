package com.luohuo.flex.im.core.chat.service;

import com.luohuo.flex.im.core.chat.mapper.GroupRunAdmissionMapper;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import com.luohuo.flex.im.domain.entity.AiclawGroupConfig;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.User;
import com.luohuo.flex.im.domain.enums.RoomTypeEnum;
import com.luohuo.flex.im.enums.UserTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * aichatoverview#485 T4：群执行准入的当前事实判定矩阵。
 *
 * <p>本测试只验证判定逻辑本身（哪个事实不成立 -> 哪个拒绝原因，以及授权代次如何随事实变化），
 * 不宣称验证了并发时序或 native 是否提交；那两类证据在行为检查与真实运行记录中。</p>
 */
@ExtendWith(MockitoExtension.class)
class GroupRunAdmissionServiceTest {

	private static final Long ACTOR = 100L;
	private static final Long ROOM = 10L;
	private static final Long TENANT = 1L;

	@Mock private GroupRunAdmissionMapper admissionMapper;

	@InjectMocks private GroupRunAdmissionService service;

	private Room groupRoom() {
		Room room = new Room();
		room.setId(ROOM);
		room.setType(RoomTypeEnum.GROUP.getType());
		room.setIsDel(false);
		return room;
	}

	private Room friendRoom() {
		Room room = new Room();
		room.setId(ROOM);
		room.setType(RoomTypeEnum.FRIEND.getType());
		room.setIsDel(false);
		return room;
	}

	private AiclawGroupConfig config(Long id, Integer approved, String workspace, LocalDateTime updateTime) {
		AiclawGroupConfig config = new AiclawGroupConfig();
		config.setId(id);
		config.setAiclawUid(ACTOR);
		config.setRoomId(ROOM);
		config.setApproved(approved);
		config.setWorkspaceDir(workspace);
		config.setUpdateTime(updateTime);
		config.setIsDel(false);
		return config;
	}

	private User identity(Long id, Integer state, Long tenantId) {
		User user = new User();
		user.setId(id);
		user.setUserType(UserTypeEnum.AICLAW.getValue());
		user.setState(state);
		user.setTenantId(tenantId);
		return user;
	}

	private Aiclaw aiclaw(Long id, Integer authStatus, Long tenantId, LocalDateTime deactivatedAt) {
		Aiclaw aiclaw = new Aiclaw();
		aiclaw.setId(id);
		aiclaw.setUid(ACTOR);
		aiclaw.setAuthStatus(authStatus);
		aiclaw.setTenantId(tenantId);
		aiclaw.setDeactivatedAt(deactivatedAt);
		return aiclaw;
	}

	/** 一套全部成立的当前事实：群在、已批准、成员在、身份激活。 */
	private void allFactsHold(AiclawGroupConfig config) {
		when(admissionMapper.selectRoom(ROOM, TENANT)).thenReturn(groupRoom());
		when(admissionMapper.lockConfig(ACTOR, ROOM)).thenReturn(config);
		when(admissionMapper.lockMembership(ACTOR, ROOM, TENANT)).thenReturn(900L);
		when(admissionMapper.lockUser(ACTOR)).thenReturn(identity(ACTOR, 0, TENANT));
		when(admissionMapper.lockAiclaw(ACTOR)).thenReturn(aiclaw(ACTOR, 1, TENANT, null));
	}

	@Nested
	@DisplayName("当前事实判定")
	class Facts {

		@Test
		@DisplayName("事实全部成立：准入并给出代次与工作目录")
		void admitted() {
			allFactsHold(config(7L, 1, "/w/acme", LocalDateTime.of(2026, 1, 1, 0, 0)));

			GroupRunAdmissionService.Admission admission =
					service.admitGroupRun(ACTOR, ROOM, TENANT, null);

			assertTrue(admission.admitted());
			assertEquals(GroupRunAdmissionService.ADMITTED, admission.getOutcome());
			assertEquals("/w/acme", admission.getWorkspaceDir());
			assertNotNull(admission.getAuthorizationEpoch());
			assertEquals(GroupRunAdmissionService.SCHEMA, admission.getSchema());
		}

		@Test
		@DisplayName("参数非法：不触达任何事实表，直接拒绝")
		void invalidArguments() {
			GroupRunAdmissionService.Admission admission = service.admitGroupRun(null, ROOM, TENANT, null);

			assertFalse(admission.admitted());
			assertEquals(GroupRunAdmissionService.REASON_INVALID, admission.getReason());
			assertNull(admission.getAuthorizationEpoch());
		}

		@Test
		@DisplayName("群已删除/不存在：room_not_accessible（与普通失败区分）")
		void roomGone() {
			when(admissionMapper.selectRoom(ROOM, TENANT)).thenReturn(null);

			GroupRunAdmissionService.Admission admission = service.admitGroupRun(ACTOR, ROOM, TENANT, null);

			assertEquals(GroupRunAdmissionService.REASON_ROOM_GONE, admission.getReason());
		}

		@Test
		@DisplayName("无配置行：authorization_missing_config（不能默认允许）")
		void missingConfig() {
			when(admissionMapper.selectRoom(ROOM, TENANT)).thenReturn(groupRoom());
			when(admissionMapper.lockConfig(ACTOR, ROOM)).thenReturn(null);

			assertEquals(GroupRunAdmissionService.REASON_CONFIG_MISSING,
					service.admitGroupRun(ACTOR, ROOM, TENANT, null).getReason());
		}

		@Test
		@DisplayName("配置被软删：占着唯一键但不再代表授权，按未批准拒绝")
		void softDeletedConfigIsNotApproval() {
			AiclawGroupConfig deleted = config(7L, 1, "/w/acme", LocalDateTime.of(2026, 1, 1, 0, 0));
			deleted.setIsDel(true);
			when(admissionMapper.selectRoom(ROOM, TENANT)).thenReturn(groupRoom());
			when(admissionMapper.lockConfig(ACTOR, ROOM)).thenReturn(deleted);

			assertEquals(GroupRunAdmissionService.REASON_NOT_APPROVED,
					service.admitGroupRun(ACTOR, ROOM, TENANT, null).getReason());
		}

		@Test
		@DisplayName("approved != 1：authorization_not_approved")
		void notApproved() {
			when(admissionMapper.selectRoom(ROOM, TENANT)).thenReturn(groupRoom());
			when(admissionMapper.lockConfig(ACTOR, ROOM))
					.thenReturn(config(7L, 0, null, LocalDateTime.of(2026, 1, 1, 0, 0)));
			when(admissionMapper.lockMembership(ACTOR, ROOM, TENANT)).thenReturn(900L);
			when(admissionMapper.lockUser(ACTOR)).thenReturn(identity(ACTOR, 0, TENANT));
			when(admissionMapper.lockAiclaw(ACTOR)).thenReturn(aiclaw(ACTOR, 1, TENANT, null));

			assertEquals(GroupRunAdmissionService.REASON_NOT_APPROVED,
					service.admitGroupRun(ACTOR, ROOM, TENANT, null).getReason());
		}

		@Test
		@DisplayName("成员行不成立：authorization_membership_revoked")
		void membershipRevoked() {
			when(admissionMapper.selectRoom(ROOM, TENANT)).thenReturn(groupRoom());
			when(admissionMapper.lockConfig(ACTOR, ROOM))
					.thenReturn(config(7L, 1, null, LocalDateTime.of(2026, 1, 1, 0, 0)));
			when(admissionMapper.lockMembership(ACTOR, ROOM, TENANT)).thenReturn(null);
			when(admissionMapper.lockUser(ACTOR)).thenReturn(identity(ACTOR, 0, TENANT));
			when(admissionMapper.lockAiclaw(ACTOR)).thenReturn(aiclaw(ACTOR, 1, TENANT, null));

			assertEquals(GroupRunAdmissionService.REASON_MEMBERSHIP_GONE,
					service.admitGroupRun(ACTOR, ROOM, TENANT, null).getReason());
		}

		@Test
		@DisplayName("身份停用/权限状态不成立：authorization_identity_inactive")
		void identityInactive() {
			allFactsHold(config(7L, 1, null, LocalDateTime.of(2026, 1, 1, 0, 0)));
			when(admissionMapper.lockAiclaw(ACTOR))
					.thenReturn(aiclaw(ACTOR, 1, TENANT, LocalDateTime.of(2026, 2, 1, 0, 0)));

			assertEquals(GroupRunAdmissionService.REASON_IDENTITY_INACTIVE,
					service.admitGroupRun(ACTOR, ROOM, TENANT, null).getReason());
		}

		@Test
		@DisplayName("单聊不套群 approved：只要成员与身份成立即可")
		void friendRoomSkipsConfig() {
			when(admissionMapper.selectRoom(ROOM, TENANT)).thenReturn(friendRoom());
			when(admissionMapper.lockMembership(ACTOR, ROOM, TENANT)).thenReturn(900L);
			when(admissionMapper.lockUser(ACTOR)).thenReturn(identity(ACTOR, 0, TENANT));
			when(admissionMapper.lockAiclaw(ACTOR)).thenReturn(aiclaw(ACTOR, 1, TENANT, null));

			assertTrue(service.admitGroupRun(ACTOR, ROOM, TENANT, null).admitted());
		}
	}

	@Nested
	@DisplayName("授权代次：旧输入不能被重新批准洗新")
	class Authority {

		@Test
		@DisplayName("携带的代次与当前事实一致：准入")
		void matchingEpochAdmitted() {
			allFactsHold(config(7L, 1, "/w/acme", LocalDateTime.of(2026, 1, 1, 0, 0)));
			String current = service.admitGroupRun(ACTOR, ROOM, TENANT, null).getAuthorizationEpoch();

			assertTrue(service.admitGroupRun(ACTOR, ROOM, TENANT, current).admitted());
		}

		@Test
		@DisplayName("撤权后重新批准：成员行重建拿到新 id，旧输入的代次即失效")
		void reapprovalAfterRevokeInvalidatesOldInput() {
			allFactsHold(config(7L, 1, "/w/acme", LocalDateTime.of(2026, 1, 1, 0, 0)));
			String before = service.admitGroupRun(ACTOR, ROOM, TENANT, null).getAuthorizationEpoch();

			when(admissionMapper.lockMembership(ACTOR, ROOM, TENANT)).thenReturn(901L);
			String after = service.admitGroupRun(ACTOR, ROOM, TENANT, null).getAuthorizationEpoch();

			assertNotEquals(before, after);
			assertEquals(GroupRunAdmissionService.REASON_EPOCH_STALE,
					service.admitGroupRun(ACTOR, ROOM, TENANT, before).getReason());
		}

		@Test
		@DisplayName("workspaceDir 变化就是执行授权上下文变化：未准入的旧任务必须重新准入")
		void workspaceChangeInvalidatesOldInput() {
			allFactsHold(config(7L, 1, "/w/acme", LocalDateTime.of(2026, 1, 1, 0, 0)));
			String before = service.admitGroupRun(ACTOR, ROOM, TENANT, null).getAuthorizationEpoch();

			when(admissionMapper.lockConfig(ACTOR, ROOM))
					.thenReturn(config(7L, 1, "/w/other", LocalDateTime.of(2026, 1, 2, 0, 0)));

			assertEquals(GroupRunAdmissionService.REASON_EPOCH_STALE,
					service.admitGroupRun(ACTOR, ROOM, TENANT, before).getReason());
		}

		@Test
		@DisplayName("身份停用后恢复：身份行重建拿到新 id，incarnation 变化让旧代次失效（ABA 不被折叠成布尔）")
		void identityReactivatedChangesEpoch() {
			allFactsHold(config(7L, 1, "/w/acme", LocalDateTime.of(2026, 1, 1, 0, 0)));
			String before = service.admitGroupRun(ACTOR, ROOM, TENANT, null).getAuthorizationEpoch();

			// 恢复走的是「停用 -> 重新激活」，身份行换新雪花 id；同 id 同状态不能假装成同一次授权。
			when(admissionMapper.lockAiclaw(ACTOR))
					.thenReturn(aiclaw(ACTOR + 500L, 1, TENANT, null));

			assertNotEquals(before, service.admitGroupRun(ACTOR, ROOM, TENANT, null).getAuthorizationEpoch());
		}

		@Test
		@DisplayName("未携带代次（旧协议/旧积压）不放松当前事实判定，也不做代次比对")
		void absentEpochStillChecksFacts() {
			when(admissionMapper.selectRoom(ROOM, TENANT)).thenReturn(groupRoom());
			when(admissionMapper.lockConfig(ACTOR, ROOM)).thenReturn(null);

			assertEquals(GroupRunAdmissionService.REASON_CONFIG_MISSING,
					service.admitGroupRun(ACTOR, ROOM, TENANT, null).getReason());
		}
	}

	@Nested
	@DisplayName("只读代次读取")
	class CurrentEpoch {

		@Test
		@DisplayName("currentEpoch 走无锁读，且与加锁准入对同一事实推导出同一代次")
		void currentEpochUsesPlainReads() {
			AiclawGroupConfig config = config(7L, 1, "/w/acme", LocalDateTime.of(2026, 1, 1, 0, 0));
			when(admissionMapper.selectRoom(ROOM, TENANT)).thenReturn(groupRoom());
			when(admissionMapper.selectConfig(ACTOR, ROOM)).thenReturn(config);
			when(admissionMapper.selectMembership(ACTOR, ROOM, TENANT)).thenReturn(900L);
			when(admissionMapper.selectUser(ACTOR)).thenReturn(identity(ACTOR, 0, TENANT));
			when(admissionMapper.selectAiclaw(ACTOR)).thenReturn(aiclaw(ACTOR, 1, TENANT, null));

			allFactsHold(config);

			String epoch = service.currentEpoch(ACTOR, ROOM, TENANT);

			assertNotNull(epoch);
			assertEquals(32, epoch.length());
			assertEquals(service.admitGroupRun(ACTOR, ROOM, TENANT, null).getAuthorizationEpoch(), epoch);
		}

		@Test
		@DisplayName("无权访问时没有代次：调用方拿不到可用的授权上下文")
		void notAccessibleHasNoEpoch() {
			when(admissionMapper.selectRoom(ROOM, TENANT)).thenReturn(null);

			assertNull(service.currentEpoch(ACTOR, ROOM, TENANT));
		}
	}
}
