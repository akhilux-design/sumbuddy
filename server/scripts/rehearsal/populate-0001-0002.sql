-- Test rows for rehearsing a migration of `groups` (0003_family.sql) on a THROWAWAY database: local (--local with a
-- throwaway --persist-to folder) or a scratch remote database made for it (docs/server.md "Build notes, family" 6).
-- Never run it against the live database `xpense`. Apply 0001 and 0002 first, run this file, then 0003, then compare
-- every table with what it held before (row counts: users 3, sessions 2, devices 1, groups 4, members 8, expenses 4,
-- expense_shares 6, settlements 3, changes 11, links 3, group_cache 2, idempotency_keys 1, access_ended 2).
-- Rows of every kind 0001 and 0002 hold: groups of each kind and state, members on and off the app, joined by link and
-- removed, live and deleted expenses with shares, settlements, links live, revoked and expired. Not real data.
INSERT INTO users (id, google_sub, name, phone, upi_id, created_at, updated_at) VALUES ('u1', 'g-1', 'Arjun Mehta', '+919000000007', 'arjun@okhdfc', 1763000000000, 1763000000000);
INSERT INTO users (id, dev_key, name, created_at, updated_at) VALUES ('u2', 'priya', 'Priya Nair', 1763000000000, 1763000000000);
INSERT INTO users (id, google_sub, name, created_at, updated_at) VALUES ('u3', 'g-3', 'Rahul Shah', 1763000000000, 1763000000001);
INSERT INTO sessions (id, user_id, refresh_hash, prev_refresh_hash, rotated_at, nonce_hash, created_at, expires_at) VALUES ('s1', 'u1', 'r1', 'r0', 1763000000000, 'n1', 1763000000000, 1768184000000);
INSERT INTO sessions (id, user_id, refresh_hash, created_at, expires_at, revoked_at) VALUES ('s2', 'u2', 'r2', 1763000000000, 1763086400000, 1763000000005);
INSERT INTO devices (installation_id, user_id, fcm_token, updated_at) VALUES ('d1', 'u1', 'fcm-1', 1763000000000);
INSERT INTO groups (id, kind, name, owner_user_id, version, created_at, updated_at) VALUES ('g-flat', 'group', 'Flat 302', 'u1', 9, 1763000000000, 1763000000009);
INSERT INTO groups (id, kind, name, owner_user_id, version, created_at, updated_at) VALUES ('g-direct', 'direct', NULL, 'u1', 3, 1763000000000, 1763000000003);
INSERT INTO groups (id, kind, name, owner_user_id, version, created_at, updated_at, deleted_at, purged_at) VALUES ('g-gone', 'group', 'Goa', 'u1', 4, 1763000000000, 1763000000004, 1763000000005, 1763000000006);
INSERT INTO groups (id, kind, name, owner_user_id, version, created_at, updated_at, deleted_at) VALUES ('g-old', 'group', 'Old', NULL, 2, 1763000000000, 1763000000002, 1763000000007);
INSERT INTO members (id, group_id, user_id, name, joined_at, version, created_at) VALUES ('m-arjun', 'g-flat', 'u1', 'Arjun Mehta', 1763000000000, 1, 1763000000000);
INSERT INTO members (id, group_id, user_id, name, added_by_member_id, joined_at, last_reminded_at, version, created_at, join_link_id, join_prev_name, join_prev_phone) VALUES ('m-priya', 'g-flat', 'u2', 'Priya', 'm-arjun', 1763000000006, 1763000000002, 6, 1763000000000, 'l1', 'Priya N', '+919000000001');
INSERT INTO members (id, group_id, user_id, name, phone, added_by_member_id, version, created_at) VALUES ('m-kiran', 'g-flat', NULL, 'Kiran', '+919800000001', 'm-arjun', 1, 1763000000000);
INSERT INTO members (id, group_id, user_id, name, added_by_member_id, joined_at, version, created_at, removed_at) VALUES ('m-rahul', 'g-flat', 'u3', 'Rahul Shah', 'm-arjun', 1763000000000, 8, 1763000000000, 1763000000008);
INSERT INTO members (id, group_id, user_id, name, joined_at, version, created_at) VALUES ('m-arjun-d', 'g-direct', 'u1', 'Arjun Mehta', 1763000000000, 1, 1763000000000);
INSERT INTO members (id, group_id, user_id, name, version, created_at) VALUES ('m-asha-d', 'g-direct', NULL, 'Asha', 1, 1763000000000);
INSERT INTO members (id, group_id, user_id, name, joined_at, version, created_at) VALUES ('m-arjun-g', 'g-gone', 'u1', 'Arjun Mehta', 1763000000000, 1, 1763000000000);
INSERT INTO members (id, group_id, user_id, name, version, created_at, removed_at) VALUES ('m-former', 'g-old', NULL, 'Former member', 2, 1763000000000, 1763000000002);
INSERT INTO expenses (id, group_id, description, category, amount_paise, occurred_on, paid_by_member_id, split_method, auto, created_by_member_id, updated_by_member_id, version, created_at, updated_at) VALUES ('e1', 'g-flat', 'Swiggy', 'FOOD', 45000, '2026-11-16', 'm-arjun', 'EQUAL', 0, 'm-arjun', 'm-arjun', 2, 1763000000000, 1763000000000);
INSERT INTO expenses (id, group_id, description, category, amount_paise, occurred_on, paid_by_member_id, split_method, auto, created_by_member_id, updated_by_member_id, version, created_at, updated_at, deleted_at) VALUES ('e2', 'g-flat', 'Rent', 'RENT_AND_BILLS', 2400000, '2026-11-01', 'm-priya', 'SHARES', 1, 'm-priya', 'm-arjun', 7, 1763000000000, 1763000000007, 1763000000007);
INSERT INTO expenses (id, group_id, description, category, amount_paise, occurred_on, paid_by_member_id, split_method, created_by_member_id, updated_by_member_id, version, created_at, updated_at) VALUES ('e3', 'g-direct', 'Movie', 'FUN', 40000, '2026-11-15', 'm-arjun-d', 'AMOUNTS', 'm-arjun-d', 'm-arjun-d', 2, 1763000000000, 1763000000000);
INSERT INTO expenses (id, group_id, description, category, amount_paise, occurred_on, paid_by_member_id, split_method, created_by_member_id, updated_by_member_id, version, created_at, updated_at) VALUES ('e4', 'g-gone', 'Villa', 'TRAVEL', 90000, '2025-12-28', 'm-arjun-g', 'PERCENT', 'm-arjun-g', 'm-arjun-g', 2, 1763000000000, 1763000000000);
INSERT INTO expense_shares (expense_id, member_id, share_paise, input) VALUES ('e1', 'm-arjun', 15000, NULL), ('e1', 'm-priya', 15000, NULL), ('e1', 'm-kiran', 15000, NULL);
INSERT INTO expense_shares (expense_id, member_id, share_paise, input) VALUES ('e3', 'm-arjun-d', 25000, 25000), ('e3', 'm-asha-d', 15000, 15000);
INSERT INTO expense_shares (expense_id, member_id, share_paise, input) VALUES ('e4', 'm-arjun-g', 90000, 10000);
INSERT INTO settlements (id, group_id, from_member_id, to_member_id, amount_paise, method, matched_from_sms, occurred_on, recorded_by_member_id, confirmed_at, version, created_at) VALUES ('st1', 'g-flat', 'm-priya', 'm-arjun', 15000, 'UPI', 1, '2026-11-17', 'm-arjun', 1763000000004, 4, 1763000000004);
INSERT INTO settlements (id, group_id, from_member_id, to_member_id, amount_paise, method, occurred_on, recorded_by_member_id, version, created_at) VALUES ('st2', 'g-flat', 'm-kiran', 'm-arjun', 5000, 'CASH', '2026-11-17', 'm-arjun', 5, 1763000000005);
INSERT INTO settlements (id, group_id, from_member_id, to_member_id, amount_paise, method, occurred_on, recorded_by_member_id, version, created_at, deleted_at) VALUES ('st3', 'g-direct', 'm-asha-d', 'm-arjun-d', 15000, 'OTHER', '2026-11-16', 'm-arjun-d', 3, 1763000000003, 1763000000003);
INSERT INTO changes (group_id, version, kind, op, entity_id, actor_member_id, created_at) VALUES
  ('g-flat', 1, 'group', 'create', 'g-flat', 'm-arjun', 1763000000001), ('g-flat', 2, 'expense', 'create', 'e1', 'm-arjun', 1763000000002),
  ('g-flat', 3, 'member', 'create', 'm-kiran', 'm-arjun', 1763000000003), ('g-flat', 4, 'settlement', 'create', 'st1', 'm-arjun', 1763000000004),
  ('g-flat', 5, 'settlement', 'create', 'st2', 'm-arjun', 1763000000005), ('g-flat', 6, 'member', 'join', 'm-priya', 'm-priya', 1763000000006),
  ('g-flat', 7, 'expense', 'delete', 'e2', 'm-arjun', 1763000000007), ('g-flat', 8, 'member', 'leave', 'm-rahul', 'm-rahul', 1763000000008),
  ('g-flat', 9, 'group', 'update', 'g-flat', 'm-arjun', 1763000000009), ('g-direct', 1, 'group', 'create', 'g-direct', 'm-arjun-d', 1763000000000),
  ('g-gone', 4, 'group', 'delete', 'g-gone', NULL, 1763000000005);
INSERT INTO links (id, token_hash, group_id, member_id, created_by_member_id, created_at, expires_at, revoked_at) VALUES ('l1', 'h1', 'g-flat', 'm-priya', 'm-arjun', 1763000000000, 1770776000000, 1763000000006);
INSERT INTO links (id, token_hash, group_id, member_id, created_by_member_id, created_at, expires_at) VALUES ('l2', 'h2', 'g-flat', 'm-kiran', 'm-arjun', 1763000000000, 1770776000000);
INSERT INTO links (id, token_hash, group_id, member_id, created_by_member_id, created_at, expires_at) VALUES ('l3', 'h3', 'g-direct', 'm-asha-d', 'm-arjun-d', 1754360000000, 1762136000000);
INSERT INTO group_cache (group_id, cache_key, version, body) VALUES ('g-flat', 'balances', 9, '{"version":9}'), ('g-flat', 'link:l2', 9, '{}');
INSERT INTO idempotency_keys (user_id, key, request_hash, status, entity_id, created_at) VALUES ('u1', 'k1', 'abc', 201, 'g-flat', 1763000000000);
INSERT INTO access_ended (user_id, group_id, reason, at) VALUES ('u3', 'g-flat', 'left', 1763000000008), ('u1', 'g-gone', 'deleted', 1763000000005);
