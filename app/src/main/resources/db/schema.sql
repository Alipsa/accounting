CREATE TABLE IF NOT EXISTS schema_version
(
    version
    INTEGER
    PRIMARY
    KEY,
    script_name
    VARCHAR
(
    255
) NOT NULL,
    installed_at TIMESTAMP NOT NULL
    );
