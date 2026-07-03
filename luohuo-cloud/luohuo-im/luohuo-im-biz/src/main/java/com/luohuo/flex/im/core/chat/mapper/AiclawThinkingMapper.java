package com.luohuo.flex.im.core.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luohuo.flex.im.domain.entity.AiclawThinking;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * <p>
 * aiclaw thinking 记录表 Mapper 接口
 * </p>
 *
 * @author server-dev
 */
@Repository
public interface AiclawThinkingMapper extends BaseMapper<AiclawThinking> {

	/**
	 * 更新 thinking 记录的 has_response 字段
	 *
	 * @param thinkingId  thinking ID
	 * @param hasResponse 是否有回复（0=无，1=有）
	 * @return 影响行数
	 */
	@Update("UPDATE im_aiclaw_thinking SET has_response = #{hasResponse} WHERE id = #{thinkingId}")
	int updateHasResponse(@Param("thinkingId") Long thinkingId, @Param("hasResponse") Integer hasResponse);

	/**
	 * 反查指定 aiclaw 在指定房间内最近一条进行中（status=0）的 thinking id
	 *
	 * @param aiclawUid aiclaw uid
	 * @param roomId    房间 ID
	 * @return 最新的进行中 thinking id，无则 null
	 */
	@Select("SELECT id FROM im_aiclaw_thinking WHERE aiclaw_uid = #{aiclawUid} AND room_id = #{roomId} AND status = 0 AND is_del = 0 ORDER BY create_time DESC LIMIT 1")
	Long selectActiveThinkingId(@Param("aiclawUid") Long aiclawUid, @Param("roomId") Long roomId);

	/**
	 * 按房间倒序（id DESC = 最近优先）游标翻页查询 thinking 归档列表（元数据 only）。
	 *
	 * <p>keyset 分页：{@code cursorId} 为上一页最后一条的 id，取 id 更小的下一批；首页传 null。
	 * <b>刻意不 select content</b>（单行可达 200KB），列表只回元数据；全文由
	 * {@code reviewThinking} 按需拉取。</p>
	 *
	 * @param roomId   房间 ID
	 * @param cursorId 游标（上一页最后一条 id），null 表示首页
	 * @param limit    取多少行（服务层传 size+1 以判定是否最后一页）
	 * @return 元数据行（content 恒为 null），按 id 倒序
	 */
	@Select("<script>SELECT id, aiclaw_uid, trigger_msg_id, duration_ms, has_response, status, create_time "
			+ "FROM im_aiclaw_thinking WHERE room_id = #{roomId} AND is_del = 0 "
			+ "<if test='cursorId != null'> AND id &lt; #{cursorId} </if>"
			+ "ORDER BY id DESC LIMIT #{limit}</script>")
	List<AiclawThinking> selectThinkingListByRoom(@Param("roomId") Long roomId, @Param("cursorId") Long cursorId, @Param("limit") int limit);
}
