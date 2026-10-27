-- Indicateurs du pilote (à lancer chaque semaine : psql "$URL" -f deploy/sql/pilot_kpi.sql)
-- Période analysée : 7 derniers jours. Adapter l'intervalle si besoin.

-- 1. Délai moyen et médian entre la déclaration et la prise en charge (minutes), par priorité
SELECT priority,
       count(*)                                                        AS bons,
       round(avg(extract(epoch FROM accepted_at - declared_at)) / 60, 1) AS delai_moyen_min,
       round((percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM accepted_at - declared_at)) / 60)::numeric, 1)
                                                                        AS delai_median_min
FROM interventions
WHERE accepted_at IS NOT NULL AND declared_at >= now() - interval '7 days'
GROUP BY priority ORDER BY priority;

-- 2. Bons incomplets : clôturés sans cause identifiée, et bons restés ouverts plus de 24 h
SELECT count(*) FILTER (WHERE status = 'CLOTURE')                                        AS clotures,
       count(*) FILTER (WHERE status = 'CLOTURE' AND coalesce(root_cause, '') = '')        AS sans_cause,
       count(*) FILTER (WHERE status NOT IN ('CLOTURE', 'ANNULE')
                        AND declared_at < now() - interval '24 hours')                    AS ouverts_plus_24h
FROM interventions
WHERE declared_at >= now() - interval '7 days';

-- 3. Pannes qui reviennent le plus (machine + type de panne, 30 jours)
SELECT m.code AS machine, c.name AS type_panne, count(*) AS occurrences,
       round(sum(extract(epoch FROM coalesce(i.stop_end_at, now()) - i.stop_start_at)) / 60) AS arret_total_min
FROM interventions i
JOIN machines m ON m.id = i.machine_id
JOIN failure_categories c ON c.id = i.category_id
WHERE i.status <> 'ANNULE' AND i.declared_at >= now() - interval '30 days'
GROUP BY m.code, c.name HAVING count(*) >= 2
ORDER BY occurrences DESC, arret_total_min DESC NULLS LAST LIMIT 10;
