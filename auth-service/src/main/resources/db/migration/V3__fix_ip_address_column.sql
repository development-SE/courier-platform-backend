ALTER TABLE login_logs 
ALTER COLUMN ip_address TYPE varchar(45) USING ip_address::varchar;