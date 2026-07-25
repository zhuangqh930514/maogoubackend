package com.maogou.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.AiUserNotification;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface AiUserNotificationMapper extends BaseMapper<AiUserNotification> {

    @Select("""
            SELECT * FROM ai_user_notification
            WHERE user_id = #{userId} AND dedupe_key = #{dedupeKey}
            LIMIT 1
            """)
    AiUserNotification selectByDedupeKey(
            @Param("userId") Long userId,
            @Param("dedupeKey") String dedupeKey
    );

    @Select("""
            SELECT * FROM ai_user_notification
            WHERE user_id = #{userId}
            ORDER BY is_read ASC, created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<AiUserNotification> selectRecent(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) FROM ai_user_notification
            WHERE user_id = #{userId} AND is_read = 0
            """)
    long countUnread(@Param("userId") Long userId);

    @Update("""
            UPDATE ai_user_notification
            SET is_read = 1, read_at = #{readAt}, updated_at = #{readAt}
            WHERE id = #{id} AND user_id = #{userId} AND is_read = 0
            """)
    int markRead(@Param("userId") Long userId, @Param("id") Long id, @Param("readAt") LocalDateTime readAt);
}
