package com.maogou.stock.service.research;

import com.maogou.stock.service.impl.research.DecisionPolicyShadow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiDecisionPolicyRelease;
import com.maogou.stock.mapper.research.AiDecisionPolicyReleaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DecisionPolicyRegistry {
    private static final Logger log = LoggerFactory.getLogger(DecisionPolicyRegistry.class);
    private final AiDecisionPolicyReleaseMapper releaseMapper;
    private final ObjectMapper objectMapper;
    private volatile DecisionPolicyConfig shadowConfig;

    public DecisionPolicyRegistry() {
        this(null, new ObjectMapper());
    }

    @Autowired
    public DecisionPolicyRegistry(AiDecisionPolicyReleaseMapper releaseMapper, ObjectMapper objectMapper) {
        this.releaseMapper = releaseMapper;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }
    public String activeVersion() {
        return "DECISION/1.1.0";
    }

    public String shadowVersion() {
        return shadowConfig().version();
    }

    public boolean shadowCanActivate(int realTradingDays) {
        return realTradingDays >= 10;
    }

    public DecisionPolicyShadow shadowPolicy() {
        return new DecisionPolicyShadow(shadowConfig());
    }

    public DecisionPolicyConfig shadowConfig() {
        DecisionPolicyConfig cached = shadowConfig;
        if (cached != null) return cached;
        DecisionPolicyConfig loaded = DecisionPolicyConfig.defaults();
        if (releaseMapper != null) {
            try {
                AiDecisionPolicyRelease release = releaseMapper.selectShadow("DECISION");
                if (release != null && release.configJson != null && !release.configJson.isBlank()) {
                    loaded = DecisionPolicyConfig.fromJson(objectMapper.readTree(release.configJson));
                }
            } catch (Exception exception) {
                log.warn("读取 DECISION Shadow 版本化配置失败，使用只读默认配置：{}", exception.getMessage());
            }
        }
        shadowConfig = loaded;
        return loaded;
    }
}
