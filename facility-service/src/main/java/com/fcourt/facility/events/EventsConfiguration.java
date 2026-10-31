package com.fcourt.facility.events;

import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = "facility.events.enabled", havingValue = "true", matchIfMissing = true)
class EventsConfiguration {

    /** Discovery's consumer runs with missing-topics-fatal=true, so the producer side declares the topic. */
    @Bean
    NewTopic branchUpdatedTopic(@Value("${facility.events.topic}") String topic,
                                @Value("${facility.events.topic-partitions:3}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }

    /**
     * Initial snapshot: branches that never produced an event (seed/demo rows inserted by SQL)
     * get version 1 so discovery can build its projection without a manual replay.
     */
    @Bean
    @ConditionalOnProperty(name = "facility.events.bootstrap-snapshots", havingValue = "true", matchIfMissing = true)
    ApplicationRunner bootstrapBranchSnapshots(JdbcTemplate jdbc, TransactionTemplate tx, BranchEventWriter writer) {
        return args -> {
            Integer published = tx.execute(status -> {
                Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(-8084)", Boolean.class);
                if (!Boolean.TRUE.equals(locked)) return 0;
                List<Long> ids = jdbc.queryForList(
                        "SELECT id FROM branches WHERE event_version = 0 ORDER BY id FOR UPDATE", Long.class);
                ids.forEach(writer::branchChanged);
                return ids.size();
            });
            LoggerFactory.getLogger(EventsConfiguration.class).info("Queued {} initial branch snapshots", published);
        };
    }
}
