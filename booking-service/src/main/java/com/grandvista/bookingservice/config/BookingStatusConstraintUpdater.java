package com.grandvista.bookingservice.config;

import com.grandvista.bookingservice.entity.BookingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Keeps the PostgreSQL CHECK constraint on {@code bookings.status} in line with {@link BookingStatus}.
 * <p>
 * Hibernate creates that constraint together with the table, but {@code ddl-auto=update} never alters an
 * existing one, so a database created before the recovery states were introduced would reject
 * PAYMENT_IN_PROGRESS and CANCELLATION_PENDING. This runner widens the constraint once; it is idempotent, only
 * ever allows MORE values, and never touches data.
 */
@Component
public class BookingStatusConstraintUpdater implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BookingStatusConstraintUpdater.class);
    private static final String CONSTRAINT = "bookings_status_check";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public BookingStatusConstraintUpdater(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> definitions = jdbc.queryForList(
                "select pg_get_constraintdef(oid) from pg_constraint where conname = ? and conrelid = 'bookings'::regclass",
                String.class, CONSTRAINT);
        if (definitions.isEmpty()) {
            return;
        }
        boolean current = Arrays.stream(BookingStatus.values())
                .allMatch(status -> definitions.get(0).contains("'" + status.name() + "'"));
        if (current) {
            return;
        }
        String allowed = Arrays.stream(BookingStatus.values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
        tx.executeWithoutResult(status -> {
            jdbc.execute("alter table bookings drop constraint " + CONSTRAINT);
            jdbc.execute("alter table bookings add constraint " + CONSTRAINT + " check (status in (" + allowed + "))");
        });
        log.info("Widened {} to allow the booking recovery states", CONSTRAINT);
    }
}
