package com.grandvista.roomservice.service.impl;

import com.grandvista.roomservice.dto.PricingRuleRequest;
import com.grandvista.roomservice.dto.PricingRuleResponse;
import com.grandvista.roomservice.entity.PricingRule;
import com.grandvista.roomservice.entity.PricingRuleType;
import com.grandvista.roomservice.exception.InvalidRequestException;
import com.grandvista.roomservice.exception.ResourceNotFoundException;
import com.grandvista.roomservice.repository.HotelRepository;
import com.grandvista.roomservice.repository.PricingRuleRepository;
import com.grandvista.roomservice.service.PricingRuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class PricingRuleServiceImpl implements PricingRuleService {

    private static final Logger log = LoggerFactory.getLogger(PricingRuleServiceImpl.class);

    private final PricingRuleRepository ruleRepository;
    private final HotelRepository hotelRepository;

    public PricingRuleServiceImpl(PricingRuleRepository ruleRepository, HotelRepository hotelRepository) {
        this.ruleRepository = ruleRepository;
        this.hotelRepository = hotelRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PricingRuleResponse> listRules() {
        return ruleRepository.findAllByOrderByRuleTypeAscIdAsc().stream().map(PricingRuleResponse::from).toList();
    }

    @Override
    @Transactional
    public PricingRuleResponse createRule(PricingRuleRequest request) {
        validate(request);
        PricingRule rule = ruleRepository.save(new PricingRule(request.name().trim(), request.ruleType(),
                request.multiplier(), seasonalOnly(request, request.startDate()), seasonalOnly(request, request.endDate()),
                occupancyOnly(request), request.hotelId(), request.roomType(), isActive(request)));
        log.info("Pricing rule id={} '{}' created ({} x{})", rule.getId(), rule.getName(), rule.getRuleType(),
                rule.getMultiplier());
        return PricingRuleResponse.from(rule);
    }

    @Override
    @Transactional
    public PricingRuleResponse updateRule(Long ruleId, PricingRuleRequest request) {
        validate(request);
        PricingRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new ResourceNotFoundException("Pricing rule " + ruleId + " not found"));
        rule.update(request.name().trim(), request.ruleType(), request.multiplier(),
                seasonalOnly(request, request.startDate()), seasonalOnly(request, request.endDate()),
                occupancyOnly(request), request.hotelId(), request.roomType(), isActive(request));
        log.info("Pricing rule id={} updated", ruleId);
        return PricingRuleResponse.from(rule);
    }

    @Override
    @Transactional
    public void deleteRule(Long ruleId) {
        if (!ruleRepository.existsById(ruleId)) {
            throw new ResourceNotFoundException("Pricing rule " + ruleId + " not found");
        }
        ruleRepository.deleteById(ruleId);
        log.info("Pricing rule id={} deleted", ruleId);
    }

    /** Cross-field rules that Bean Validation annotations cannot express. */
    private void validate(PricingRuleRequest request) {
        if (request.ruleType() == PricingRuleType.SEASONAL) {
            if (request.startDate() == null || request.endDate() == null) {
                throw new InvalidRequestException("SEASONAL rules require startDate and endDate");
            }
            if (request.endDate().isBefore(request.startDate())) {
                throw new InvalidRequestException("endDate must not be before startDate");
            }
        } else if (request.minOccupancyPercent() == null) {
            throw new InvalidRequestException("OCCUPANCY rules require minOccupancyPercent");
        }
        if (request.hotelId() != null && !hotelRepository.existsById(request.hotelId())) {
            throw new ResourceNotFoundException("Hotel " + request.hotelId() + " not found");
        }
    }

    private static LocalDate seasonalOnly(PricingRuleRequest request, LocalDate value) {
        return request.ruleType() == PricingRuleType.SEASONAL ? value : null;
    }

    private static Integer occupancyOnly(PricingRuleRequest request) {
        return request.ruleType() == PricingRuleType.OCCUPANCY ? request.minOccupancyPercent() : null;
    }

    private static boolean isActive(PricingRuleRequest request) {
        return request.active() == null || request.active();
    }
}
