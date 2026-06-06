CREATE TABLE courier_documents (
    id              UUID PRIMARY KEY,
    courier_id      UUID NOT NULL REFERENCES courier_profiles(id) ON DELETE CASCADE,
    document_type   VARCHAR(50) NOT NULL CHECK (document_type IN ('IDENTIFICATION', 'DRIVERS_LICENSE')),
    document_number VARCHAR(50) NOT NULL,
    file_url        VARCHAR(512) NOT NULL,
    status          VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    rejection_reason TEXT,
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_courier_document_type UNIQUE (courier_id, document_type)
);

CREATE INDEX idx_courier_documents_courier_id ON courier_documents(courier_id);
