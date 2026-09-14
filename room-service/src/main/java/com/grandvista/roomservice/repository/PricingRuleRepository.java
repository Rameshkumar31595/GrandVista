package com.grandvista.roomservice.repository;

import com.grandvista.roomservice.entity.PricingRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PricingRuleRepository extends JpaRepository<PricingRule, Long> {

    List<PricingRule> findByActiveTrue();

    List<PricingRule> findAllByOrderByRuleTypeAscIdAsc();
}
