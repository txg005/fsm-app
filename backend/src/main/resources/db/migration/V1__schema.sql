CREATE TABLE employees (
 id uuid PRIMARY KEY, display_name text NOT NULL, login varchar(100) NOT NULL UNIQUE CHECK(login=lower(login))
);
CREATE TABLE sites (id uuid PRIMARY KEY, name text NOT NULL, address text NOT NULL);
CREATE TABLE equipment (id uuid PRIMARY KEY, site_id uuid NOT NULL REFERENCES sites(id), name text NOT NULL, inventory_number text NOT NULL);
CREATE INDEX ON equipment(site_id);
CREATE TABLE work_orders (
 id uuid PRIMARY KEY, equipment_id uuid NOT NULL REFERENCES equipment(id), assignee_id uuid NOT NULL REFERENCES employees(id),
 title text NOT NULL, description text NOT NULL, due_at timestamptz, status text NOT NULL CHECK(status IN ('ASSIGNED','CLOSED')),
 revision bigint NOT NULL CHECK(revision BETWEEN 1 AND 9007199254740991), measurement_specs jsonb NOT NULL CHECK(jsonb_typeof(measurement_specs)='array')
);
CREATE INDEX ON work_orders(equipment_id);
CREATE INDEX ON work_orders(assignee_id);
CREATE TABLE checklist_items (
 id uuid PRIMARY KEY, work_order_id uuid NOT NULL REFERENCES work_orders(id), position integer NOT NULL CHECK(position>=0),
 text text NOT NULL, required boolean NOT NULL, UNIQUE(work_order_id,position)
);
CREATE TABLE inspection_results (
 id uuid PRIMARY KEY, work_order_id uuid NOT NULL UNIQUE REFERENCES work_orders(id), answers jsonb NOT NULL, readings jsonb NOT NULL,
 comment text NOT NULL CHECK(char_length(comment)<=4000), location jsonb NOT NULL, captured_at timestamptz NOT NULL
);
CREATE TABLE photos (
 id uuid PRIMARY KEY, server_photo_id uuid NOT NULL UNIQUE, work_order_id uuid NOT NULL REFERENCES work_orders(id),
 owner_id uuid NOT NULL REFERENCES employees(id), result_id uuid REFERENCES inspection_results(id), position integer,
 mime_type text NOT NULL CHECK(mime_type='image/jpeg'), byte_size bigint NOT NULL CHECK(byte_size BETWEEN 1 AND 10485760),
 sha256 char(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'), created_at timestamptz NOT NULL, storage_key text NOT NULL,
 state text NOT NULL CHECK(state IN ('STAGED','ATTACHED')),
 CHECK ((state='STAGED' AND result_id IS NULL AND position IS NULL) OR (state='ATTACHED' AND result_id IS NOT NULL AND position>=0)),
 UNIQUE(result_id,position)
);
CREATE INDEX ON photos(work_order_id);
CREATE INDEX ON photos(owner_id);
CREATE INDEX ON photos(result_id);
CREATE TABLE auth_credentials(employee_id uuid PRIMARY KEY REFERENCES employees(id), password_hash text NOT NULL);
CREATE TABLE refresh_sessions(
 id uuid PRIMARY KEY, employee_id uuid NOT NULL REFERENCES employees(id), token_hash char(64) NOT NULL UNIQUE,
 expires_at timestamptz NOT NULL, revoked_at timestamptz
);
CREATE INDEX ON refresh_sessions(employee_id);
CREATE TABLE idempotency_receipts (
 employee_id uuid NOT NULL REFERENCES employees(id), operation_id uuid NOT NULL, method text NOT NULL, path text NOT NULL,
 semantic_hash char(64) NOT NULL, status integer NOT NULL CHECK(status IN (200,201)), body text NOT NULL,
 PRIMARY KEY(employee_id,operation_id)
);
-- Ensure canonical status/result consistency at commit, not halfway through close.
CREATE FUNCTION check_order_result() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE oid uuid; s text; has_result boolean;
BEGIN
 IF TG_TABLE_NAME='work_orders' THEN oid := COALESCE(NEW.id,OLD.id); ELSE oid := COALESCE(NEW.work_order_id,OLD.work_order_id); END IF;
 SELECT status INTO s FROM work_orders WHERE id=oid;
 SELECT EXISTS(SELECT 1 FROM inspection_results WHERE work_order_id=oid) INTO has_result;
 IF s IS NOT NULL AND ((s='CLOSED')<>has_result) THEN RAISE EXCEPTION 'order/result invariant' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER order_result_status AFTER INSERT OR UPDATE ON work_orders DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_order_result();
CREATE CONSTRAINT TRIGGER result_order_status AFTER INSERT OR UPDATE OR DELETE ON inspection_results DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_order_result();
CREATE FUNCTION immutable_result() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'immutable result' USING ERRCODE='23514'; END $$;
CREATE TRIGGER result_immutable BEFORE UPDATE OR DELETE ON inspection_results FOR EACH ROW EXECUTE FUNCTION immutable_result();
