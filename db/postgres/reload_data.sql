DO $$
DECLARE
    v_host text;
BEGIN
    SELECT inet_server_addr()::text
    INTO v_host;

    IF v_host <> '10.141.44.18/32' THEN
        RAISE EXCEPTION 'Refusing to run: connected to % instead of the approved server', v_host;
    END IF;
END
$$;

