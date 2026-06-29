-- Communication delivery handlers bind tenant context before updating delivery state.
GRANT EXECUTE ON FUNCTION assert_no_mixed_context() TO pms_worker;
