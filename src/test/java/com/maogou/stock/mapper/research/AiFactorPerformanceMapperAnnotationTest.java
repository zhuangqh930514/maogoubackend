package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class AiFactorPerformanceMapperAnnotationTest {

    @Test
    void dynamicSqlAnnotationsAreWellFormed() {
        assertThatCode(() -> new MybatisConfiguration().addMapper(AiFactorPerformanceMapper.class))
                .doesNotThrowAnyException();
    }
}
