package com.grandvista.authservice.service;

import com.grandvista.authservice.dto.UpdateRoleRequest;
import com.grandvista.authservice.dto.UpdateUserRequest;
import com.grandvista.authservice.dto.UserResponse;

import java.util.List;

/**
 * Administrator operations on user accounts.
 */
public interface UserManagementService {

    List<UserResponse> listUsers();

    UserResponse changeRole(Long targetUserId, UpdateRoleRequest request, Long actingAdminId);

    UserResponse updateUser(Long targetUserId, UpdateUserRequest request, Long actingAdminId);

    void deleteUser(Long targetUserId, Long actingAdminId);
}
