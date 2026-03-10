package com.hmdp.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.hmdp.utils.SentinelResources;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class SentinelRuleConfig {

    @PostConstruct
    public void initRules() {
        List<FlowRule> rules = new ArrayList<>();

        FlowRule shopQueryByIdRule = new FlowRule();
        shopQueryByIdRule.setResource(SentinelResources.SHOP_QUERY_BY_ID);
        shopQueryByIdRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        shopQueryByIdRule.setCount(200);
        shopQueryByIdRule.setLimitApp("default");
        rules.add(shopQueryByIdRule);

        FlowRule voucherSeckillRule = new FlowRule();
        voucherSeckillRule.setResource(SentinelResources.VOUCHER_SECKILL);
        voucherSeckillRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        voucherSeckillRule.setCount(100);
        voucherSeckillRule.setLimitApp("default");
        rules.add(voucherSeckillRule);

        FlowRuleManager.loadRules(rules);
    }
}
