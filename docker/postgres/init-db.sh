#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE account;
    CREATE USER account_service WITH PASSWORD '$ACCOUNT_DB_PASSWORD';
    GRANT ALL PRIVILEGES ON DATABASE account TO account_service;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "account" <<-EOSQL
    GRANT ALL PRIVILEGES ON SCHEMA public TO account_service;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO account_service;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE transfer;
    CREATE USER transfer_service WITH PASSWORD '$TRANSFER_DB_PASSWORD';
    GRANT ALL PRIVILEGES ON DATABASE transfer TO transfer_service;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "transfer" <<-EOSQL
    GRANT ALL PRIVILEGES ON SCHEMA public TO transfer_service;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO transfer_service;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE notification;
    CREATE USER notification_service WITH PASSWORD '$NOTIFICATION_DB_PASSWORD';
    GRANT ALL PRIVILEGES ON DATABASE notification TO notification_service;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "notification" <<-EOSQL
    GRANT ALL PRIVILEGES ON SCHEMA public TO notification_service;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO notification_service;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE fraud;
    CREATE USER fraud_service WITH PASSWORD '$FRAUD_DB_PASSWORD';
    GRANT ALL PRIVILEGES ON DATABASE fraud TO fraud_service;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "fraud" <<-EOSQL
    GRANT ALL PRIVILEGES ON SCHEMA public TO fraud_service;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO fraud_service;
EOSQL
