package com.seatly.account.view;

import com.seatly.account.Role;

public record CurrentUser(Long id, String email, String displayName, Role role) {
}
