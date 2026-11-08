package com.fcourt.voucher;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class VoucherUsageMigrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test void upgradesExistingApplicationsWithoutInventingOriginalPaymentTimes() {
        var before=Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).target("2").load();
        before.migrate();var jdbc=new JdbcTemplate(before.getConfiguration().getDataSource());
        long voucher=jdbc.queryForObject("""
                INSERT INTO vouchers(code,branch_id,discount_type,discount_value,min_order_amount,start_date,end_date,usage_limit,used_count,status)
                VALUES ('LEGACY',7,'FIXED_AMOUNT',10.25,0,'2025-01-01','2030-01-01',10,1,'ACTIVE') RETURNING id
                """,Long.class);
        var recorded=Instant.parse("2026-01-02T03:04:05Z");UUID paid=UUID.randomUUID();
        for(int booking=1;booking<=3;booking++){
            jdbc.update("""
                    INSERT INTO voucher_redemptions(id,voucher_id,booking_id,customer_id,branch_id,original_amount,discount_amount,final_amount,expires_at,status,payment_id,updated_at)
                    VALUES (?,?,?,?,7,100,10.25,89.75,'2030-01-01',?,?,?)
                    """,booking==1?paid:UUID.randomUUID(),voucher,booking,booking*10,
                    booking==1?"APPLIED":booking==2?"RESERVED":"RELEASED",booking==1?99:null,java.sql.Timestamp.from(recorded));
        }
        var after=Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).load();after.migrate();
        var usage=jdbc.queryForMap("SELECT * FROM voucher_usages");
        assertThat(usage.get("id")).isEqualTo(paid);assertThat(usage.get("user_id")).isEqualTo(10L);
        assertThat((java.math.BigDecimal)usage.get("discount_amount")).isEqualByComparingTo("10.25");
        assertThat(((java.sql.Timestamp)usage.get("used_at")).toInstant()).isEqualTo(recorded);
        assertThat(usage.get("used_at_source")).isEqualTo("RECORDED_AT");
        assertThat(after.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_usages",Long.class)).isEqualTo(1);
    }
}
