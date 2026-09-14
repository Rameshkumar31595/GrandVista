package com.grandvista.roomservice.service;

import com.grandvista.roomservice.dto.PricingRuleRequest;
import com.grandvista.roomservice.dto.PricingRuleResponse;

import java.util.List;

public interface PricingRuleService {

    List<PricingRuleResponse> listRules();

    PricingRuleResponse createRule(PricingRuleRequest request);

    PricingRuleResponse updateRule(Long ruleId, PricingRuleRequest request);

    void deleteRule(Long ruleId);
}
