package com.islevendor.pay.audit;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes payments.audit_events (append-only, enforced by a database trigger).
 * Runs inside the caller's transaction, so an action and its audit entry
 * are committed together or not at all. Never put secrets or full account
 * numbers in the metadata.
 */
@Component
public class AuditLog {

    public enum ActorType { USER, SERVICE, SYSTEM }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = JsonMapper.builder().build();

    public AuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(ActorType actorType, UUID actorUserId, String action, String targetType, String targetId,
            Map<String, ?> metadata) {
        jdbc.update("""
                insert into payments.audit_events (actor_type, actor_user_id, action, target_type, target_id, metadata)
                values (?, ?, ?, ?, ?, ?::jsonb)
                """, actorType.name(), actorUserId, action, targetType, targetId, json.writeValueAsString(metadata));
    }
}
