package com.maogou.stock.service.impl.research;

import com.maogou.stock.mapper.research.AiTrainingDatasetItemMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiTrainingReadinessServiceImplSpringWiringTest {

    @Test
    void springCanConstructReadinessServiceFromItsMapperDependency() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AiTrainingDatasetItemMapper.class,
                    () -> mock(AiTrainingDatasetItemMapper.class));
            context.register(AiTrainingReadinessServiceImpl.class);

            context.refresh();

            assertThat(context.getBean(AiTrainingReadinessServiceImpl.class)).isNotNull();
        }
    }
}
