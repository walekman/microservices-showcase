package com.showcase.e2e.support;

import java.util.UUID;

public record OpenedAccount(UUID id, TestUser owner) {
}
