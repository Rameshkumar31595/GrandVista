package com.grandvista.authservice.controller;

import com.grandvista.authservice.dto.UpdateRoleRequest;
import com.grandvista.authservice.dto.UserResponse;
import com.grandvista.authservice.service.UserManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/auth/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "User administration (ADMIN)")
public class UserAdminController {

    private final UserManagementService userManagementService;

    public UserAdminController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @GetMapping
    @Operation(summary = "List all user accounts")
    public List<UserResponse> listUsers() {
        return userManagementService.listUsers();
    }

    @PatchMapping("/{userId}/role")
    @Operation(summary = "Promote/demote a user - the only way to create additional administrators")
    public UserResponse changeRole(@PathVariable Long userId, @Valid @RequestBody UpdateRoleRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        return userManagementService.changeRole(userId, request, Long.valueOf(jwt.getSubject()));
    }
}
