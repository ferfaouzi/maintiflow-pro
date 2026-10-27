-- Production : séparer le propriétaire du schéma (migrations) du rôle utilisé par l'API.
-- Le rôle applicatif ne peut ni TRUNCATE ni DELETE les tables de traçabilité, ni modifier le schéma.
-- À exécuter en tant que propriétaire après "alembic upgrade head".
CREATE ROLE maintiflow_app LOGIN PASSWORD 'A_REMPLACER';
GRANT USAGE ON SCHEMA public TO maintiflow_app;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO maintiflow_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO maintiflow_app;
-- Seules ces tables admettent une suppression applicative (pièces avant clôture, sessions expirées) :
GRANT DELETE ON parts_used, user_sessions TO maintiflow_app;
REVOKE UPDATE ON audit_log, intervention_events FROM maintiflow_app;
