-- =====================================================
-- run_all.sql  —  full database rebuild from scratch
-- =====================================================
-- Run from BDD/Scripts/ :
--
--   mysql -u <user> -p < run_all.sql
--
-- SOURCE paths are relative to the working directory
-- of the mysql process, so always invoke from here.
-- =====================================================

-- Schema (drop & recreate database, all tables)
SOURCE DBSetUp.sql;
SOURCE DataInsertion.sql;
