-- Tag MRP per size as read at the sorting table: [{"size":"S","mrp":1000,"pieces":10}, ...].
-- Null for cartons sorted before this change (they only have a total MRP).
ALTER TABLE handling_unit ADD COLUMN price_lines jsonb;
