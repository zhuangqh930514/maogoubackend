package com.maogou.stock.mapper.v2;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.v2.AiLabelCostEvidence;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface AiLabelCostEvidenceMapper extends BaseMapper<AiLabelCostEvidence> {

    @Insert("""
            <script>
            INSERT INTO ai_label_cost_evidence (
                label_id, cost_model_version, currency, quantity, entry_notional, exit_notional,
                buy_commission_rate, sell_commission_rate, stamp_duty_rate, transfer_fee_rate,
                slippage_bps, buy_commission_amount, sell_commission_amount, stamp_duty_amount,
                transfer_fee_amount, slippage_amount, total_cost_amount, evidence_json,
                source_fingerprint, created_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (
                    #{item.labelId}, #{item.costModelVersion}, #{item.currency}, #{item.quantity},
                    #{item.entryNotional}, #{item.exitNotional}, #{item.buyCommissionRate},
                    #{item.sellCommissionRate}, #{item.stampDutyRate}, #{item.transferFeeRate},
                    #{item.slippageBps}, #{item.buyCommissionAmount}, #{item.sellCommissionAmount},
                    #{item.stampDutyAmount}, #{item.transferFeeAmount}, #{item.slippageAmount},
                    #{item.totalCostAmount}, #{item.evidenceJson}, #{item.sourceFingerprint}, #{item.createdAt}
                )
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatchImmutable(@Param("items") List<AiLabelCostEvidence> items);
}
