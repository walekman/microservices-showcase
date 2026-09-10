-- Account Service database
CREATE DATABASE account;
CREATE USER account_service WITH PASSWORD 'account_service';
GRANT ALL PRIVILEGES ON DATABASE account TO account_service;
