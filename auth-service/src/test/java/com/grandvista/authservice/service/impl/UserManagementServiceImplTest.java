package com.grandvista.authservice.service.impl;

import com.grandvista.authservice.demo.DemoAccessGuard;
import com.grandvista.authservice.dto.UpdateUserRequest;
import com.grandvista.authservice.entity.Role;
import com.grandvista.authservice.entity.User;
import com.grandvista.authservice.exception.BusinessRuleException;
import com.grandvista.authservice.exception.DuplicateEmailException;
import com.grandvista.authservice.exception.ResourceNotFoundException;
import com.grandvista.authservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserManagementServiceImplTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long USER_ID = 2L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private DemoAccessGuard demoAccessGuard;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserManagementServiceImpl service;

    private User user(long id, String name, String email, Role role) {
        User u = new User(name, email, "hash", role);
        try {
            var f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return u;
    }

    @Test
    void listUsersHidesSoftDeletedAccounts() {
        User active = user(2L, "Asha", "asha@example.com", Role.ROLE_USER);
        User deleted = user(3L, "Old", "old@example.com", Role.ROLE_USER);
        deleted.softDelete();
        when(userRepository.findAllByDeletedAtIsNull(Sort.by("id"))).thenReturn(List.of(active));

        var result = service.listUsers();

        assertThat(result).extracting("email").containsExactly("asha@example.com");
    }

    @Test
    void updateUserTrimsAndNormalizesNameAndEmail() {
        User target = user(USER_ID, "Old Name", "old@example.com", Role.ROLE_USER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(target));
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);

        var response = service.updateUser(USER_ID,
                new UpdateUserRequest("  New Name  ", "  New@Example.COM ", null), ADMIN_ID);

        assertThat(target.getName()).isEqualTo("New Name");
        assertThat(target.getEmail()).isEqualTo("new@example.com");
        assertThat(target.getPassword()).isEqualTo("hash");
        assertThat(response.email()).isEqualTo("new@example.com");
        verify(passwordEncoder, never()).encode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void updateUserRehashesPasswordWhenProvided() {
        User target = user(USER_ID, "Asha", "asha@example.com", Role.ROLE_USER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(target));
        when(passwordEncoder.encode("Newpass1")).thenReturn("$2a$10$newhash");

        service.updateUser(USER_ID, new UpdateUserRequest("Asha", "asha@example.com", "Newpass1"), ADMIN_ID);

        assertThat(target.getPassword()).isEqualTo("$2a$10$newhash");
    }

    @Test
    void updateUserRejectsDuplicateEmail() {
        User target = user(USER_ID, "Asha", "asha@example.com", Role.ROLE_USER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(target));
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.updateUser(USER_ID,
                new UpdateUserRequest("Asha", "taken@example.com", null), ADMIN_ID))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void updateUserRejectsReservedDemoDomain() {
        User target = user(USER_ID, "Asha", "asha@example.com", Role.ROLE_USER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.updateUser(USER_ID,
                new UpdateUserRequest("Asha", "x@demo.grandvista.local", null), ADMIN_ID))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void updateUserRejectsDemoAccountTarget() {
        User demo = user(USER_ID, "Manager", "demo.manager@demo.grandvista.local", Role.ROLE_ADMIN);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(demo));

        assertThatThrownBy(() -> service.updateUser(USER_ID,
                new UpdateUserRequest("Renamed", "demo.manager@demo.grandvista.local", null), ADMIN_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Demo accounts");
    }

    @Test
    void updateUserOnDeletedUserIsNotFound() {
        User deleted = user(USER_ID, "Gone", "gone@example.com", Role.ROLE_USER);
        deleted.softDelete();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.updateUser(USER_ID,
                new UpdateUserRequest("Gone", "gone@example.com", null), ADMIN_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteUserSoftDeletesAndKeepsRow() {
        User target = user(USER_ID, "Asha", "asha@example.com", Role.ROLE_USER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(target));

        service.deleteUser(USER_ID, ADMIN_ID);

        assertThat(target.isDeleted()).isTrue();
        assertThat(target.getDeletedAt()).isNotNull();
        verify(userRepository, never()).delete(org.mockito.ArgumentMatchers.any(User.class));
        verify(userRepository, never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void deleteUserRefusesSelfDelete() {
        assertThatThrownBy(() -> service.deleteUser(ADMIN_ID, ADMIN_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("own account");
    }

    @Test
    void deleteUserRefusesLastAdministrator() {
        User lastAdmin = user(USER_ID, "Only Admin", "only@example.com", Role.ROLE_ADMIN);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(lastAdmin));
        when(userRepository.countByRoleAndDeletedAtIsNull(Role.ROLE_ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteUser(USER_ID, ADMIN_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("last administrator");
    }

    @Test
    void deleteUserRefusesDemoAccountTarget() {
        User demo = user(USER_ID, "Manager", "demo.manager@demo.grandvista.local", Role.ROLE_ADMIN);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(demo));

        assertThatThrownBy(() -> service.deleteUser(USER_ID, ADMIN_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Demo accounts");
    }

    @Test
    void demoAdminCannotDeleteAdministrator() {
        User targetAdmin = user(USER_ID, "Real Admin", "real@example.com", Role.ROLE_ADMIN);
        User demoActor = user(ADMIN_ID, "Demo Manager", "demo.manager@demo.grandvista.local", Role.ROLE_ADMIN);
        when(demoAccessGuard.restrictDemoAdmin()).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(targetAdmin));
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(demoActor));

        assertThatThrownBy(() -> service.deleteUser(USER_ID, ADMIN_ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deleteUserOnDeletedUserIsNotFound() {
        User deleted = user(USER_ID, "Gone", "gone@example.com", Role.ROLE_USER);
        deleted.softDelete();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.deleteUser(USER_ID, ADMIN_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
