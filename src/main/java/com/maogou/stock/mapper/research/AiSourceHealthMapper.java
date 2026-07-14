package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiSourceHealth;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiSourceHealthMapper extends BaseMapper<AiSourceHealth> {

    @Select("""
            SELECT * FROM ai_source_health
            WHERE provider_code = #{providerCode} AND endpoint_type = #{endpointType}
            LIMIT 1
            """)
    AiSourceHealth selectHealth(
            @Param("providerCode") String providerCode,
            @Param("endpointType") String endpointType
    );

    @Insert("""
            INSERT INTO ai_source_health (
                provider_code, endpoint_type, source_status, last_attempt_at, last_success_at,
                consecutive_failure_count, cooldown_until, last_error_message,
                last_response_fingerprint, created_at, updated_at
            ) VALUES (
                #{providerCode}, #{endpointType}, #{sourceStatus}, #{lastAttemptAt}, #{lastSuccessAt},
                #{consecutiveFailureCount}, #{cooldownUntil}, #{lastErrorMessage},
                #{lastResponseFingerprint}, #{createdAt}, #{updatedAt}
            )
            ON DUPLICATE KEY UPDATE
                source_status = VALUES(source_status),
                last_attempt_at = VALUES(last_attempt_at),
                last_success_at = COALESCE(VALUES(last_success_at), last_success_at),
                consecutive_failure_count = VALUES(consecutive_failure_count),
                cooldown_until = VALUES(cooldown_until),
                last_error_message = VALUES(last_error_message),
                last_response_fingerprint = COALESCE(VALUES(last_response_fingerprint), last_response_fingerprint),
                updated_at = VALUES(updated_at)
            """)
    int upsert(AiSourceHealth health);
}
