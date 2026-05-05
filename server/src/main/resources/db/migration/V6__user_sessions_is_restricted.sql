-- Add is_restricted column to user_sessions table for restricted session tracking
-- Restricted sessions are created from unacknowledged IPs and cannot acknowledge IPs
ALTER TABLE user_sessions ADD COLUMN is_restricted BOOLEAN NOT NULL DEFAULT FALSE;
