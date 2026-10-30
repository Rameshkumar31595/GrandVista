package com.grandvista.authservice.service.impl;

import com.grandvista.authservice.demo.DemoAccessGuard;
import com.grandvista.authservice.demo.DemoAccount;
import com.grandvista.authservice.dto.UpdateRoleRequest;
import com.grandvista.authservice.dto.UpdateUserRequest;
import com.grandvista.authservice.exception.DuplicateEmailException;
import com.grandvista.authservice.dto.UserResponse;
import com.grandvista.authservice.entity.Role;
import com.grandvista.authservice.entity.User;
import com.grandvista.authservice.exception.BusinessRuleException;
import com.grandvista.authservice.exception.ResourceNotFoundException;
import com.grandvista.authservice.repository.UserRepository;
import com.grandvista.authservice.service.UserManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserManagementServiceImpl implements UserManagementService {

    private static final Logger log = LoggerFactory.getLogger(UserManagementServiceImpl.class);

    private final UserRepository userRepository;
    private final DemoAccessGuard demoAccessGuard;
    private final PasswordEncoder passwordEncoder;

    public UserManagementServiceImpl(UserRepository userRepository, DemoAccessGuard demoAccessGuard,
                                     PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.demoAccessGuard = demoAccessGuard;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return userRepository.findAllByDeletedAtIsNull(Sort.by("id")).stream().map(UserResponse::from).toList();
    }

    @Override
    @Transactional
    public UserResponse changeRole(Long targetUserId, UpdateRoleRequest request, Long actingAdminId) {
        if (targetUserId.equals(actingAdminId)) {
            // Prevents an administrator from accidentally locking themselves out.
            throw new BusinessRuleException("Administrators cannot change their own role");
        }
        User user = findActiveUser(targetUserId);
        if (demoAccessGuard.restrictDemoAdmin() && actingAsDemoAccount(actingAdminId)
                && (request.role() == Role.ROLE_ADMIN || user.getRole() == Role.ROLE_ADMIN)) {
            // Public deployment: whoever holds the shared demo administrator must not be able to mint or remove admins.
            log.warn("Demo administrator id={} tried to change administrator rights of user id={} - refused",
                    actingAdminId, targetUserId);
            throw new AccessDeniedException("Demo administrator cannot change administrator rights");
        }
        user.setRole(request.role());
        log.info("Admin id={} changed role of user id={} to {}", actingAdminId, targetUserId, request.role());
        return UserResponse.from(user);
    }

    @Override
    @Transactional
    public UserResponse updateUser(Long targetUserId, UpdateUserRequest request, Long actingAdminId) {
        User user = findActiveUser(targetUserId);
        rejectReservedAccount(user, "modified");
        rejectDemoAdminTouchingAdmin(user, actingAdminId, "modify");
        String email = request.email().trim().toLowerCase();
        if (!email.equals(user.getEmail())) {
            if (DemoAccount.isReservedEmail(email)) {
                throw new BusinessRuleException("This e-mail domain is reserved");
            }
            if (userRepository.existsByEmail(email)) {
                throw new DuplicateEmailException(email);
            }
        }
        user.setName(request.name().trim());
        user.setEmail(email);
        if (request.password() != null) {
            user.setPassword(passwordEncoder.encode(request.password()));
        }
        log.info("Admin id={} updated profile of user id={}", actingAdminId, targetUserId);
        return UserResponse.from(user);
    }

    @Override
    @Transactional
    public void deleteUser(Long targetUserId, Long actingAdminId) {
        if (targetUserId.equals(actingAdminId)) {
            // Prevents an administrator from accidentally locking themselves out.
            throw new BusinessRuleException("Administrators cannot delete their own account");
        }
        User user = findActiveUser(targetUserId);
        rejectReservedAccount(user, "deleted");
        rejectDemoAdminTouchingAdmin(user, actingAdminId, "delete");
        if (user.getRole() == Role.ROLE_ADMIN
                && userRepository.countByRoleAndDeletedAtIsNull(Role.ROLE_ADMIN) <= 1) {
            throw new BusinessRuleException("Cannot delete the last administrator");
        }
        // Soft delete: the row stays so bookings/payments in the other services keep their user id.
        user.softDelete();
        log.info("Admin id={} deleted (soft) user id={}", actingAdminId, targetUserId);
    }

    private User findActiveUser(Long targetUserId) {
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User " + targetUserId + " not found"));
        if (user.isDeleted()) {
            throw new ResourceNotFoundException("User " + targetUserId + " not found");
        }
        return user;
    }

    private void rejectReservedAccount(User user, String action) {
        if (DemoAccount.isReservedEmail(user.getEmail())) {
            throw new BusinessRuleException("Demo accounts cannot be " + action);
        }
    }

    private void rejectDemoAdminTouchingAdmin(User target, Long actingAdminId, String action) {
        if (demoAccessGuard.restrictDemoAdmin() && actingAsDemoAccount(actingAdminId)
                && target.getRole() == Role.ROLE_ADMIN) {
            log.warn("Demo administrator id={} tried to {} administrator id={} - refused",
                    actingAdminId, action, target.getId());
            throw new AccessDeniedException("Demo administrator cannot " + action + " administrator accounts");
        }
    }

    private boolean actingAsDemoAccount(Long actingAdminId) {
        return userRepository.findById(actingAdminId).map(actor -> DemoAccount.isReservedEmail(actor.getEmail()))
                .orElse(true);
    }
}
