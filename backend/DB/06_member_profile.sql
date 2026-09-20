USE esun_shop;

SET @add_display_name = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE member ADD COLUMN display_name VARCHAR(100) NULL AFTER password_hash',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'member' AND column_name = 'display_name'
);
PREPARE add_display_name_statement FROM @add_display_name;
EXECUTE add_display_name_statement;
DEALLOCATE PREPARE add_display_name_statement;

SET @add_phone = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE member ADD COLUMN phone VARCHAR(30) NULL AFTER display_name',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'member' AND column_name = 'phone'
);
PREPARE add_phone_statement FROM @add_phone;
EXECUTE add_phone_statement;
DEALLOCATE PREPARE add_phone_statement;
