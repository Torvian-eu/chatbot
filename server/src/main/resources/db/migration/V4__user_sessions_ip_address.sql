-- Add ip_address column to user_sessions table for session tracking
-- Supports IPv6 addresses with varchar(45)
ALTER TABLE user_sessions ADD COLUMN ip_address VARCHAR(45);
