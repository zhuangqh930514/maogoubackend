package com.maogou.stock.mapper.research;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Reads the applied research schema contract without allowing application code to mutate it. */
public interface AiResearchSchemaVersionMapper {

    @Select("""
            SELECT status FROM ai_research_schema_version
            WHERE version_no = #{versionNo}
            LIMIT 1
            """)
    String selectStatus(@Param("versionNo") String versionNo);
}
