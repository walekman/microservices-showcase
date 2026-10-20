package com.showcase.account.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface AccountOperationJpaRepository extends JpaRepository<AccountOperationJpaEntity, String> {
}
