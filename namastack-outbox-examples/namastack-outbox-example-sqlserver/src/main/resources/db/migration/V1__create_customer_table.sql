CREATE TABLE customer
(
    id        UNIQUEIDENTIFIER NOT NULL,
    firstname VARCHAR(255)     NOT NULL,
    lastname  VARCHAR(255)     NOT NULL,
    email     VARCHAR(255)     NOT NULL,
    PRIMARY KEY (id)
);

