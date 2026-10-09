CREATE TABLE IF NOT EXISTS policy_rules (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    type       TEXT    NOT NULL CHECK (type IN ('DOMAIN','KEYWORD','IP')),
    value      TEXT    NOT NULL,
    action     TEXT    NOT NULL DEFAULT 'BLOCK' CHECK (action IN ('BLOCK','ALLOW')),
    priority   INTEGER NOT NULL DEFAULT 100,
    enabled    INTEGER NOT NULL DEFAULT 1,
    note       TEXT,
    created_at TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
);

CREATE TABLE IF NOT EXISTS access_logs (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp   TEXT    NOT NULL,
    client_ip   TEXT,
    method      TEXT,
    host        TEXT,
    path        TEXT,
    decision    TEXT    NOT NULL CHECK (decision IN ('BLOCK','ALLOW')),
    rule_id     INTEGER,
    status_code INTEGER
);

CREATE INDEX IF NOT EXISTS idx_logs_host     ON access_logs(host);
CREATE INDEX IF NOT EXISTS idx_logs_decision ON access_logs(decision);
CREATE INDEX IF NOT EXISTS idx_logs_time     ON access_logs(timestamp DESC);