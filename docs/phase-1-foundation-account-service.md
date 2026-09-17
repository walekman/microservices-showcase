# Foundation + Account Service Implementation Phase

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this phase task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the repo's multi-module Maven build and Docker Compose skeleton, and deliver a working, independently-testable Account Service (create accounts, fetch balance, debit/credit with optimistic locking) reachable via `docker compose up`.

**Architecture:** A Maven reactor (root aggregator POM inheriting `spring-boot-starter-parent`) with one module so far, `account-service`. Account Service is a standard layered Spring Boot app: JPA entity/repository (`domain`), a thin orchestration layer (`service`), and a REST layer (`api`) with centralized exception mapping. Postgres runs as its own Docker Compose service; Account Service gets its own database inside it, provisioned by an init script (database-per-service).

**Tech Stack:** Java 21, Spring Boot 3.3.4, Spring Data JPA, PostgreSQL 16, Maven (with Maven Wrapper), Testcontainers, JUnit 5 + AssertJ + Mockito, Docker Compose.

**Spec:** [docs/microservices-showcase-design.md](microservices-showcase-design.md)

## Global Constraints

- Java 21 floor; Spring Boot 3.x. (spec §3)
- Spring MVC (blocking), not WebFlux; virtual threads enabled via `spring.threads.virtual.enabled=true`. No thread-pinning diagnostics/verification tooling — explicitly descoped during brainstorming. (spec §3)
- Spring Data JPA + PostgreSQL, one logical database per stateful service — no service queries another's database. (spec §2, §3)
- Integration-level tests use Testcontainers against real Postgres/Kafka — never H2 or mocks at that layer. (spec §6)
- No centralized log aggregation (ELK/Loki); structured JSON logs with MDC trace correlation only, once tracing is wired up in a later phase. (spec §5)
- No Kubernetes/service mesh; local deployment is Docker Compose only, single `docker compose up`. (spec §7)
- Kafka runs in KRaft mode (no Zookeeper) when it's introduced. (spec §3) — not touched in this phase.
- Transactional outbox is published via scheduled polling, not Debezium, when it's introduced. (spec §4) — not touched in this phase.

---

### Task 1: Project scaffolding

**Files:**
- Create: `pom.xml` (root reactor POM)
- Create: `account-service/pom.xml`
- Create: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` (generated)
- Create: `account-service/src/main/java/com/showcase/account/AccountServiceApplication.java`
- Create: `account-service/src/main/resources/application.yml`
- Test: `account-service/src/test/java/com/showcase/account/AccountServiceApplicationTests.java`
- Create: `docker-compose.yml`
- Create: `docker/postgres/init-db.sql`
- Create: `.gitignore`

**Interfaces:**
- Produces: a buildable Maven reactor (`./mvnw test` runs from repo root), the `account-service` module skeleton later tasks add to, and a `postgres` Docker Compose service exposing port 5432 with an `account` database + `account_service` user already provisioned.

- [ ] **Step 1: Create the root reactor POM**

```xml
<!-- pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
    <relativePath/>
  </parent>

  <groupId>com.showcase</groupId>
  <artifactId>microservices-showcase</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <modules>
    <module>account-service</module>
  </modules>

  <properties>
    <java.version>21</java.version>
  </properties>
</project>
```

- [ ] **Step 2: Create the `account-service` module POM**

```xml
<!-- account-service/pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.showcase</groupId>
    <artifactId>microservices-showcase</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>account-service</artifactId>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Generate the Maven wrapper**

Run: `mvn org.apache.maven.plugins:maven-wrapper-plugin:3.3.2:wrapper -Dmaven=3.9.9`
Expected: creates `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties` at the repo root.

- [ ] **Step 4: Write the failing smoke test**

```java
// account-service/src/test/java/com/showcase/account/AccountServiceApplicationTests.java
package com.showcase.account;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AccountServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 5: Run it and confirm it fails**

Run: `./mvnw -pl account-service test -Dtest=AccountServiceApplicationTests`
Expected: FAIL — compilation error, cannot find symbol `AccountServiceApplication`.

- [ ] **Step 6: Write the application class and config**

```java
// account-service/src/main/java/com/showcase/account/AccountServiceApplication.java
package com.showcase.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AccountServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
```

```yaml
# account-service/src/main/resources/application.yml
spring:
  application:
    name: account-service
  threads:
    virtual:
      enabled: true
```

- [ ] **Step 7: Run it and confirm it passes**

Run: `./mvnw -pl account-service test -Dtest=AccountServiceApplicationTests`
Expected: PASS.

- [ ] **Step 8: Add Docker Compose with Postgres**

```yaml
# docker-compose.yml
services:
  postgres:
    image: postgres:16-alpine
    container_name: showcase-postgres
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - ./docker/postgres/init-db.sql:/docker-entrypoint-initdb.d/init-db.sql
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 5s
      retries: 5

volumes:
  postgres-data:
```

```sql
-- docker/postgres/init-db.sql
-- Account Service database
CREATE DATABASE account;
CREATE USER account_service WITH PASSWORD 'account_service';
GRANT ALL PRIVILEGES ON DATABASE account TO account_service;
```

- [ ] **Step 9: Verify Postgres starts cleanly**

Run: `docker compose up -d postgres` then `docker compose ps`
Expected: `showcase-postgres` shows as `healthy`.

- [ ] **Step 10: Add `.gitignore`**

```
target/
*.class
.idea/
*.iml
.DS_Store
```

- [ ] **Step 11: Commit**

```bash
git add pom.xml account-service/pom.xml mvnw mvnw.cmd .mvn account-service/src docker-compose.yml docker/postgres/init-db.sql .gitignore
git commit -m "chore: scaffold Maven reactor, Account Service skeleton, Postgres compose service"
```

---

### Task 2: Account entity, repository, and optimistic locking

**Files:**
- Modify: `account-service/pom.xml`
- Modify: `account-service/src/main/resources/application.yml`
- Create: `account-service/src/main/java/com/showcase/account/domain/Account.java`
- Create: `account-service/src/main/java/com/showcase/account/domain/InsufficientFundsException.java`
- Create: `account-service/src/main/java/com/showcase/account/domain/AccountRepository.java`
- Test: `account-service/src/test/java/com/showcase/account/domain/AccountTest.java`
- Test: `account-service/src/test/java/com/showcase/account/domain/AccountRepositoryTest.java`

**Interfaces:**
- Consumes: Postgres compose service and `account` database from Task 1.
- Produces: `Account(String ownerName, BigDecimal balance)` with `getId(): UUID`, `getOwnerName(): String`, `getBalance(): BigDecimal`, `getVersion(): long`, `getCreatedAt(): Instant`, `debit(BigDecimal amount)`, `credit(BigDecimal amount)` (both void, mutate balance in place); `InsufficientFundsException extends RuntimeException`; `AccountRepository extends JpaRepository<Account, UUID>`. Later tasks build on these exact names and signatures.

- [ ] **Step 1: Add JPA/Postgres/Testcontainers dependencies**

```xml
<!-- account-service/pom.xml: add inside <dependencies> -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-testcontainers</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>junit-jupiter</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>postgresql</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Add datasource and JPA config**

```yaml
# account-service/src/main/resources/application.yml
spring:
  application:
    name: account-service
  threads:
    virtual:
      enabled: true
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:account}
    username: ${DB_USER:account_service}
    password: ${DB_PASSWORD:account_service}
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
```

- [ ] **Step 3: Write the failing entity unit test**

```java
// account-service/src/test/java/com/showcase/account/domain/AccountTest.java
package com.showcase.account.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    @Test
    void debitReducesBalanceWhenFundsAreSufficient() {
        Account account = new Account("Ada Lovelace", new BigDecimal("100.00"));

        account.debit(new BigDecimal("40.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("60.00");
    }

    @Test
    void debitThrowsWhenFundsAreInsufficient() {
        Account account = new Account("Ada Lovelace", new BigDecimal("30.00"));

        assertThatThrownBy(() -> account.debit(new BigDecimal("40.00")))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("30.00");
    }

    @Test
    void creditIncreasesBalance() {
        Account account = new Account("Ada Lovelace", new BigDecimal("100.00"));

        account.credit(new BigDecimal("25.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("125.00");
    }
}
```

- [ ] **Step 4: Run it and confirm it fails**

Run: `./mvnw -pl account-service test -Dtest=AccountTest`
Expected: FAIL — compilation error, `Account`/`InsufficientFundsException` do not exist.

- [ ] **Step 5: Write the entity and exception**

```java
// account-service/src/main/java/com/showcase/account/domain/InsufficientFundsException.java
package com.showcase.account.domain;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(UUID accountId, BigDecimal requested, BigDecimal available) {
        super("Account %s has insufficient funds: requested %s, available %s"
                .formatted(accountId, requested, available));
    }
}
```

```java
// account-service/src/main/java/com/showcase/account/domain/Account.java
package com.showcase.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String ownerName;

    // scale 2, not more: AmountRequest already restricts every debit/credit to 2 decimal
    // places (@Digits(fraction = 2)), and this project has no interest/FX/proration that
    // would need sub-cent intermediate precision -- see docs/microservices-showcase-design.md's
    // non-goals. A wider scale here would be unused headroom, not a real requirement.
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Version
    private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
        // for JPA
    }

    public Account(String ownerName, BigDecimal balance) {
        this.ownerName = ownerName;
        this.balance = balance;
        this.createdAt = Instant.now();
    }

    public void debit(BigDecimal amount) {
        if (amount.compareTo(balance) > 0) {
            throw new InsufficientFundsException(id, amount, balance);
        }
        balance = balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        balance = balance.add(amount);
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

- [ ] **Step 6: Run it and confirm it passes**

Run: `./mvnw -pl account-service test -Dtest=AccountTest`
Expected: PASS.

- [ ] **Step 7: Write the failing repository/persistence test**

```java
// account-service/src/test/java/com/showcase/account/domain/AccountRepositoryTest.java
package com.showcase.account.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class AccountRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void savesAndReloadsAnAccount() {
        Account saved = accountRepository.save(new Account("Ada Lovelace", new BigDecimal("100.00")));

        Optional<Account> found = accountRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getOwnerName()).isEqualTo("Ada Lovelace");
        assertThat(found.get().getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void concurrentUpdatesAreRejectedByOptimisticLocking() {
        Account saved = accountRepository.save(new Account("Ada Lovelace", new BigDecimal("100.00")));
        accountRepository.flush();

        Account firstRead = accountRepository.findById(saved.getId()).orElseThrow();
        Account secondRead = accountRepository.findById(saved.getId()).orElseThrow();

        firstRead.credit(new BigDecimal("10.00"));
        accountRepository.saveAndFlush(firstRead);

        secondRead.credit(new BigDecimal("5.00"));
        assertThatThrownBy(() -> accountRepository.saveAndFlush(secondRead))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
```

- [ ] **Step 8: Run it and confirm it fails**

Run: `./mvnw -pl account-service test -Dtest=AccountRepositoryTest`
Expected: FAIL — compilation error, `AccountRepository` does not exist.

- [ ] **Step 9: Write the repository**

```java
// account-service/src/main/java/com/showcase/account/domain/AccountRepository.java
package com.showcase.account.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
}
```

- [ ] **Step 10: Run it and confirm it passes**

Run: `./mvnw -pl account-service test -Dtest=AccountRepositoryTest`
Expected: PASS. (Requires Docker running locally — Testcontainers starts a real Postgres container for this test.)

- [ ] **Step 11: Commit**

```bash
git add account-service/pom.xml account-service/src
git commit -m "feat: add Account entity, repository, and optimistic locking"
```

---

### Task 3: AccountService business logic

**Files:**
- Create: `account-service/src/main/java/com/showcase/account/domain/AccountNotFoundException.java`
- Create: `account-service/src/main/java/com/showcase/account/service/AccountService.java`
- Test: `account-service/src/test/java/com/showcase/account/service/AccountServiceTest.java`

**Interfaces:**
- Consumes: `Account` and `AccountRepository` (Task 2), `InsufficientFundsException` (Task 2, thrown by `Account.debit`, not caught here — propagates).
- Produces: `AccountService` with `createAccount(String ownerName, BigDecimal initialBalance): Account`, `getAccount(UUID id): Account`, `debit(UUID id, BigDecimal amount): Account`, `credit(UUID id, BigDecimal amount): Account`; `AccountNotFoundException extends RuntimeException`. Task 4's controller calls these four methods directly.

- [ ] **Step 1: Write the failing service test**

```java
// account-service/src/test/java/com/showcase/account/service/AccountServiceTest.java
package com.showcase.account.service;

import com.showcase.account.domain.Account;
import com.showcase.account.domain.AccountNotFoundException;
import com.showcase.account.domain.AccountRepository;
import com.showcase.account.domain.InsufficientFundsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository);
    }

    @Test
    void createAccountSavesANewAccount() {
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Account result = accountService.createAccount("Ada Lovelace", new BigDecimal("100.00"));

        assertThat(result.getOwnerName()).isEqualTo("Ada Lovelace");
        assertThat(result.getBalance()).isEqualByComparingTo("100.00");
        verify(accountRepository).save(any(Account.class));
    }

    @Test
    void getAccountThrowsWhenAccountIsMissing() {
        UUID id = UUID.randomUUID();
        when(accountRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccount(id))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void debitReducesBalanceAndSaves() {
        UUID id = UUID.randomUUID();
        Account account = new Account("Ada Lovelace", new BigDecimal("100.00"));
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        Account result = accountService.debit(id, new BigDecimal("40.00"));

        assertThat(result.getBalance()).isEqualByComparingTo("60.00");
    }

    @Test
    void debitThrowsWhenFundsAreInsufficientAndDoesNotSave() {
        UUID id = UUID.randomUUID();
        Account account = new Account("Ada Lovelace", new BigDecimal("10.00"));
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.debit(id, new BigDecimal("40.00")))
                .isInstanceOf(InsufficientFundsException.class);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void creditIncreasesBalanceAndSaves() {
        UUID id = UUID.randomUUID();
        Account account = new Account("Ada Lovelace", new BigDecimal("100.00"));
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        Account result = accountService.credit(id, new BigDecimal("25.00"));

        assertThat(result.getBalance()).isEqualByComparingTo("125.00");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./mvnw -pl account-service test -Dtest=AccountServiceTest`
Expected: FAIL — compilation error, `AccountService`/`AccountNotFoundException` do not exist.

- [ ] **Step 3: Write the exception and service**

```java
// account-service/src/main/java/com/showcase/account/domain/AccountNotFoundException.java
package com.showcase.account.domain;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID accountId) {
        super("Account not found: " + accountId);
    }
}
```

```java
// account-service/src/main/java/com/showcase/account/service/AccountService.java
package com.showcase.account.service;

import com.showcase.account.domain.Account;
import com.showcase.account.domain.AccountNotFoundException;
import com.showcase.account.domain.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public Account createAccount(String ownerName, BigDecimal initialBalance) {
        return accountRepository.save(new Account(ownerName, initialBalance));
    }

    @Transactional(readOnly = true)
    public Account getAccount(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional
    public Account debit(UUID id, BigDecimal amount) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        account.debit(amount);
        return accountRepository.save(account);
    }

    @Transactional
    public Account credit(UUID id, BigDecimal amount) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        account.credit(amount);
        return accountRepository.save(account);
    }
}
```

- [ ] **Step 4: Run it and confirm it passes**

Run: `./mvnw -pl account-service test -Dtest=AccountServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add account-service/src
git commit -m "feat: add AccountService orchestration layer"
```

---

### Task 4: REST API

**Files:**
- Modify: `account-service/pom.xml`
- Modify: `account-service/src/main/resources/application.yml`
- Create: `account-service/src/main/java/com/showcase/account/api/CreateAccountRequest.java`
- Create: `account-service/src/main/java/com/showcase/account/api/AmountRequest.java`
- Create: `account-service/src/main/java/com/showcase/account/api/AccountResponse.java`
- Create: `account-service/src/main/java/com/showcase/account/api/ErrorResponse.java`
- Create: `account-service/src/main/java/com/showcase/account/api/AccountController.java`
- Create: `account-service/src/main/java/com/showcase/account/api/ApiExceptionHandler.java`
- Test: `account-service/src/test/java/com/showcase/account/api/AccountControllerIT.java`

**Interfaces:**
- Consumes: `AccountService` (Task 3) — `createAccount`, `getAccount`, `debit`, `credit`; `Account` getters (Task 2).
- Produces: `POST /accounts`, `GET /accounts/{id}`, `POST /accounts/{id}/debit`, `POST /accounts/{id}/credit` — the HTTP surface Task 5's Docker Compose wiring and later phases' Transfer Service call into.

- [ ] **Step 1: Add web and validation dependencies**

```xml
<!-- account-service/pom.xml: add inside <dependencies> -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

- [ ] **Step 2: Add the server port**

```yaml
# account-service/src/main/resources/application.yml: add at top level
server:
  port: 8081
```

- [ ] **Step 3: Write the failing REST integration test**

```java
// account-service/src/test/java/com/showcase/account/api/AccountControllerIT.java
package com.showcase.account.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AccountControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createsAndFetchesAnAccount() {
        ResponseEntity<AccountResponse> createResponse = restTemplate.postForEntity(
                "/accounts", new CreateAccountRequest("Ada Lovelace", new BigDecimal("100.00")), AccountResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = createResponse.getBody().id();

        ResponseEntity<AccountResponse> getResponse = restTemplate.getForEntity("/accounts/" + id, AccountResponse.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().balance()).isEqualByComparingTo("100.00");
    }

    @Test
    void returns404ForUnknownAccount() {
        ResponseEntity<ErrorResponse> response = restTemplate.getForEntity(
                "/accounts/" + UUID.randomUUID(), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void debitsAnAccountSuccessfully() {
        UUID id = createAccount(new BigDecimal("100.00"));

        ResponseEntity<AccountResponse> response = restTemplate.postForEntity(
                "/accounts/" + id + "/debit", new AmountRequest(new BigDecimal("40.00")), AccountResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().balance()).isEqualByComparingTo("60.00");
    }

    @Test
    void rejectsDebitWithInsufficientFunds() {
        UUID id = createAccount(new BigDecimal("10.00"));

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/accounts/" + id + "/debit", new AmountRequest(new BigDecimal("40.00")), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void rejectsNegativeInitialBalance() {
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/accounts", new CreateAccountRequest("Ada Lovelace", new BigDecimal("-5.00")), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private UUID createAccount(BigDecimal initialBalance) {
        ResponseEntity<AccountResponse> response = restTemplate.postForEntity(
                "/accounts", new CreateAccountRequest("Ada Lovelace", initialBalance), AccountResponse.class);
        return response.getBody().id();
    }
}
```

- [ ] **Step 4: Run it and confirm it fails**

Run: `./mvnw -pl account-service test -Dtest=AccountControllerIT`
Expected: FAIL — compilation error, DTOs/controller/exception handler do not exist.

- [ ] **Step 5: Write the DTOs**

```java
// account-service/src/main/java/com/showcase/account/api/CreateAccountRequest.java
package com.showcase.account.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotBlank String ownerName,
        @NotNull @DecimalMin(value = "0.00") BigDecimal initialBalance) {
}
```

```java
// account-service/src/main/java/com/showcase/account/api/AmountRequest.java
package com.showcase.account.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AmountRequest(
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount) {
}
```

```java
// account-service/src/main/java/com/showcase/account/api/AccountResponse.java
package com.showcase.account.api;

import com.showcase.account.domain.Account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String ownerName,
        BigDecimal balance,
        Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getOwnerName(), account.getBalance(), account.getCreatedAt());
    }
}
```

```java
// account-service/src/main/java/com/showcase/account/api/ErrorResponse.java
package com.showcase.account.api;

import java.time.Instant;

public record ErrorResponse(String message, Instant timestamp) {

    public static ErrorResponse of(String message) {
        return new ErrorResponse(message, Instant.now());
    }
}
```

- [ ] **Step 6: Write the controller and exception handler**

```java
// account-service/src/main/java/com/showcase/account/api/AccountController.java
package com.showcase.account.api;

import com.showcase.account.domain.Account;
import com.showcase.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.createAccount(request.ownerName(), request.initialBalance());
        return ResponseEntity.created(URI.create("/accounts/" + account.getId()))
                .body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    public AccountResponse getAccount(@PathVariable UUID id) {
        return AccountResponse.from(accountService.getAccount(id));
    }

    @PostMapping("/{id}/debit")
    public AccountResponse debit(@PathVariable UUID id, @Valid @RequestBody AmountRequest request) {
        return AccountResponse.from(accountService.debit(id, request.amount()));
    }

    @PostMapping("/{id}/credit")
    public AccountResponse credit(@PathVariable UUID id, @Valid @RequestBody AmountRequest request) {
        return AccountResponse.from(accountService.credit(id, request.amount()));
    }
}
```

```java
// account-service/src/main/java/com/showcase/account/api/ApiExceptionHandler.java
package com.showcase.account.api;

import com.showcase.account.domain.AccountNotFoundException;
import com.showcase.account.domain.InsufficientFundsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(AccountNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(ex.getMessage()));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ErrorResponse.of(ex.getMessage()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("Account was modified concurrently, please retry"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.badRequest().body(ErrorResponse.of(message));
    }
}
```

- [ ] **Step 7: Run it and confirm it passes**

Run: `./mvnw -pl account-service test -Dtest=AccountControllerIT`
Expected: PASS.

- [ ] **Step 8: Run the full test suite**

Run: `./mvnw -pl account-service test`
Expected: PASS — all tests from Tasks 1-4 green.

- [ ] **Step 9: Commit**

```bash
git add account-service/pom.xml account-service/src
git commit -m "feat: add Account Service REST API and error handling"
```

---

### Task 5: Docker Compose wiring and README

**Files:**
- Create: `account-service/Dockerfile`
- Modify: `docker-compose.yml`
- Create: `README.md`

**Interfaces:**
- Consumes: the full Account Service application (Tasks 1-4) and the `postgres` compose service (Task 1).
- Produces: `docker compose up --build` brings up a working Account Service on `localhost:8081`, backed by its own Postgres database — the demoable deliverable for this phase.

- [ ] **Step 1: Write the Dockerfile**

```dockerfile
# account-service/Dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY account-service/pom.xml account-service/pom.xml
RUN chmod +x mvnw && ./mvnw -pl account-service -am dependency:go-offline -B
COPY account-service/src account-service/src
RUN ./mvnw -pl account-service -am package -DskipTests -B

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/account-service/target/account-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Add the service to Docker Compose**

```yaml
# docker-compose.yml: add under services:
  account-service:
    build:
      context: .
      dockerfile: account-service/Dockerfile
    container_name: showcase-account-service
    environment:
      DB_HOST: postgres
      DB_PORT: 5432
      DB_NAME: account
      DB_USER: account_service
      DB_PASSWORD: account_service
    ports:
      - "8081:8081"
    depends_on:
      postgres:
        condition: service_healthy
```

- [ ] **Step 3: Build and start the stack**

Run: `docker compose up --build -d`
Expected: `docker compose ps` shows `showcase-postgres` healthy and `showcase-account-service` running.

- [ ] **Step 4: Verify end-to-end manually**

Run:
```bash
curl -s -X POST http://localhost:8081/accounts \
  -H "Content-Type: application/json" \
  -d '{"ownerName": "Ada Lovelace", "initialBalance": 100.00}'
```
Expected: `201` response body with an `id`, `ownerName: "Ada Lovelace"`, `balance: 100.00`.

Run (substitute the `id` from the previous response):
```bash
curl -s -X POST http://localhost:8081/accounts/<id>/debit \
  -H "Content-Type: application/json" \
  -d '{"amount": 40.00}'
```
Expected: `200` response body with `balance: 60.00`.

- [ ] **Step 5: Write the README**

```markdown
# Banking Microservices Showcase

Design: [docs/microservices-showcase-design.md](docs/microservices-showcase-design.md)

## Running locally

    docker compose up --build

This starts Postgres and Account Service. More services land in later phases.

## Try it

    # Create an account
    curl -X POST http://localhost:8081/accounts \
      -H "Content-Type: application/json" \
      -d '{"ownerName": "Ada Lovelace", "initialBalance": 100.00}'

    # Fetch it (replace <id> with the id from the response above)
    curl http://localhost:8081/accounts/<id>

    # Debit it
    curl -X POST http://localhost:8081/accounts/<id>/debit \
      -H "Content-Type: application/json" \
      -d '{"amount": 40.00}'

    # Credit it
    curl -X POST http://localhost:8081/accounts/<id>/credit \
      -H "Content-Type: application/json" \
      -d '{"amount": 15.00}'
```

- [ ] **Step 6: Commit**

```bash
git add account-service/Dockerfile docker-compose.yml README.md
git commit -m "feat: wire Account Service into Docker Compose, add README"
```

---

## Deferred / Known Gaps

- **Flyway vs. `ddl-auto` for schema management.** This phase's final review raised it and deferred it; re-raised and re-deferred again in Phase 4 (the outbox table was added to Transfer's existing schema via `ddl-auto: update`, no migration tooling introduced) and in Phase 4's own Scope Boundary table ("its own phase"). Revisit before a phase adds substantial new schema surface — Phase 5 added none, so the revisit trigger wasn't tripped there.

## Next Phase

Phase 2 — **Transfer + Fraud Services (the Saga)** — adds the `transfer` and `fraud` databases/services, the orchestrated saga in Transfer Service, Resilience4j-wrapped calls to Account Service, the transactional outbox, and Kafka (KRaft mode) to Docker Compose.
