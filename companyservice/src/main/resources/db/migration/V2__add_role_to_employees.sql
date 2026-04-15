-- Add role column to employees table.
-- Existing rows default to MANAGER to preserve backward compatibility.
ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS role VARCHAR(30) NOT NULL DEFAULT 'MANAGER';
