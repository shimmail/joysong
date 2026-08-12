-- Existing certified professionals receive the same zero-balance USD wallets
-- that are provisioned immediately for newly approved identities.
INSERT INTO wallets (owner_type, owner_id, currency)
SELECT 'DOCTOR', ur.user_id, 'USD'
FROM user_roles ur
WHERE ur.role_code = 'DOCTOR' AND ur.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(wallets.id);

INSERT INTO wallets (owner_type, owner_id, currency)
SELECT 'CONSULTANT', ur.user_id, 'USD'
FROM user_roles ur
WHERE ur.role_code = 'CONSULTANT' AND ur.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(wallets.id);

INSERT INTO wallets (owner_type, owner_id, currency)
SELECT DISTINCT 'INSTITUTION', im.institution_id, 'USD'
FROM institution_memberships im
JOIN user_roles ur
  ON ur.user_id = im.user_id
 AND ur.role_code = 'INSTITUTION_LEGAL_REPRESENTATIVE'
 AND ur.status = 'ACTIVE'
WHERE im.member_role IN ('INSTITUTION_LEGAL_REPRESENTATIVE', 'LEGAL_REPRESENTATIVE')
  AND im.status = 'APPROVED'
ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(wallets.id);
