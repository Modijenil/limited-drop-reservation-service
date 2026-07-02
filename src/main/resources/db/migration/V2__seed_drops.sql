INSERT INTO drops (name, description, status, opens_at, closes_at, unit_price, currency, total_units, available_units, held_units, confirmed_units, version, created_at, updated_at)
VALUES
('Sneaker Drop A', 'Limited sneakers size mix', 'OPEN', UTC_TIMESTAMP(6), DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 DAY), 199.9900, 'USD', 50, 50, 0, 0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
('Concert Drop B', 'VIP section ticket release', 'OPEN', UTC_TIMESTAMP(6), DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 4 HOUR), 120.0000, 'USD', 20, 20, 0, 0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
('Table Drop C', 'Restaurant prime-time seating slots', 'OPEN', UTC_TIMESTAMP(6), DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 2 HOUR), 0.0000, 'USD', 12, 12, 0, 0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
