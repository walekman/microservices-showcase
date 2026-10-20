package com.showcase.account.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, UUID> {

    List<AccountJpaEntity> findAllByOwnerId(UUID ownerId);

    boolean existsByOwnerId(UUID ownerId);
}
