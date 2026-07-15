package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AiFactorValueMapperAnnotationTest {

    @Test
    void enabledDefinitionsMapFactorVersionToTheLegacyVersionNoProperty() throws Exception {
        Select select = AiFactorValueMapper.class
                .getMethod("selectEnabledDefinitions", String.class)
                .getAnnotation(Select.class);

        assertThat(String.join("\n", select.value()))
                .contains("factor_version AS versionNo");
        assertThatCode(() -> new MybatisConfiguration().addMapper(AiFactorValueMapper.class))
                .doesNotThrowAnyException();
    }
}
