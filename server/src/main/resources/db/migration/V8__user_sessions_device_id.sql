-- Add device_id column to user_sessions table for device tracking
-- Stores the client-side UUID that identifies the device creating the session
ALTER TABLE user_sessions ADD COLUMN device_id VARCHAR(36) NOT NULL DEFAULT 'unknown';
