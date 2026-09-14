package com.grandvista.roomservice.controller;

import com.grandvista.roomservice.dto.PricingRuleRequest;
import com.grandvista.roomservice.dto.PricingRuleResponse;
import com.grandvista.roomservice.service.PricingRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/pricing-rules")
@Tag(name = "Dynamic pricing rules (ADMIN)")
public class PricingRuleController {

    private final PricingRuleService pricingRuleService;

    public PricingRuleController(PricingRuleService pricingRuleService) {
        this.pricingRuleService = pricingRuleService;
    }

    @GetMapping
    @Operation(summary = "List all pricing rules")
    public List<PricingRuleResponse> listRules() {
        return pricingRuleService.listRules();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a SEASONAL or OCCUPANCY pricing rule")
    public PricingRuleResponse createRule(@Valid @RequestBody PricingRuleRequest request) {
        return pricingRuleService.createRule(request);
    }

    @PutMapping("/{ruleId}")
    @Operation(summary = "Update a pricing rule")
    public PricingRuleResponse updateRule(@PathVariable Long ruleId, @Valid @RequestBody PricingRuleRequest request) {
        return pricingRuleService.updateRule(ruleId, request);
    }

    @DeleteMapping("/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a pricing rule")
    public void deleteRule(@PathVariable Long ruleId) {
        pricingRuleService.deleteRule(ruleId);
    }
}
