package com.grandvista.authservice.service.impl;

import com.grandvista.authservice.dto.UpdateRoleRequest;
import com.grandvista.authservice.dto.UserResponse;
import com.grandvista.authservice.entity.User;
import com.grandvista.authservice.exception.BusinessRuleException;
import com.grandvista.authservice.exception.ResourceNotFoundException;
import com.grandvista.authservice.repository.UserRepository;
import com.grandvista.authservice.service.UserManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserManagementServiceImpl implements UserManagementService {

    private static final Logger log = LoggerFactory.getLogger(UserManagementServiceImpl.class);

    private final UserRepository userRepository;

    public UserManagementServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return userRepository.findAll(Sort.by("id")).stream().map(UserResponse::from).toList();
    }

    @Override
    @Transactional
    public UserResponse changeRole(Long targetUserId, UpdateRoleRequest request, Long actingAdminId) {
        if (targetUserId.equals(actingAdminId)) {
            // Prevents an administrator from accidentally locking themselves out.
            throw new BusinessRuleException("Administrators cannot change their own role");
        }
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User " + targetUserId + " not found"));
        user.setRole(request.role());
        log.info("Admin id={} changed role of user id={} to {}", actingAdminId, targetUserId, request.role());
        return UserResponse.from(user);
    }
}
