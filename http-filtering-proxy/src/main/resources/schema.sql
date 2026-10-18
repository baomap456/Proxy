CREATE TABLE IF NOT EXISTS policy_rules (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    type       ENUM('DOMAIN','KEYWORD','IP') NOT NULL,
    value      VARCHAR(255) NOT NULL,
    action     ENUM('BLOCK','ALLOW') NOT NULL DEFAULT 'BLOCK',
    priority   INT NOT NULL DEFAULT 100,
    enabled    TINYINT(1) NOT NULL DEFAULT 1,
    note       VARCHAR(255),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS access_logs (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `timestamp` DATETIME NOT NULL,
    client_ip   VARCHAR(45),
    method      VARCHAR(16),
    host        VARCHAR(255),
    path        TEXT,
    decision    ENUM('BLOCK','ALLOW') NOT NULL,
    rule_id     INT,
    status_code INT,
    INDEX idx_logs_host (host),
    INDEX idx_logs_decision (decision),
    INDEX idx_logs_time (`timestamp` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;