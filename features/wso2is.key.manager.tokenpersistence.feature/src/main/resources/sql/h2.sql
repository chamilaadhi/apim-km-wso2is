  CREATE TABLE IF NOT EXISTS AM_INVALID_TOKENS (
     UUID VARCHAR(255) NOT NULL,
     SIGNATURE VARCHAR(2048) NOT NULL,
     CONSUMER_KEY VARCHAR(255) NOT NULL,
     TIME_CREATED TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
     EXPIRY_TIMESTAMP BIGINT NOT NULL,
     PRIMARY KEY (UUID)
  );

  CREATE TABLE IF NOT EXISTS AM_INTERNAL_TOKEN_REVOCATION (
      ID VARCHAR(255) NOT NULL,
      CONSUMER_KEY VARCHAR(255) NOT NULL,
      TOKEN_TYPE VARCHAR(255) NOT NULL,
      TIME_REVOKED VARCHAR(255) NOT NULL,
      PRIMARY KEY (ID)
  );
