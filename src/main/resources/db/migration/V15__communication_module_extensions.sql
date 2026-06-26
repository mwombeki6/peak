-- V15: Communication Module Extensions
CREATE TABLE communication_templates (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name text NOT NULL,
    subject text,
    content text NOT NULL,
    channel_type character varying(20) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT communication_templates_pkey PRIMARY KEY (id),
    CONSTRAINT communication_templates_tenant_name_key UNIQUE (tenant_id, name)
);

