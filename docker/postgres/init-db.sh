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
