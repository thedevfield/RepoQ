#!/bin/sh
set -eu

: "${AUTH_DB_PASSWORD:?}"
: "${PROJECT_DB_PASSWORD:?}"
: "${INTERVIEW_DB_PASSWORD:?}"

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v auth_password="$AUTH_DB_PASSWORD" \
  -v project_password="$PROJECT_DB_PASSWORD" \
  -v interview_password="$INTERVIEW_DB_PASSWORD" <<'SQL'
REVOKE ALL ON DATABASE devfield_dev FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;

CREATE ROLE devfield_auth LOGIN PASSWORD :'auth_password';
CREATE ROLE devfield_project LOGIN PASSWORD :'project_password';
CREATE ROLE devfield_interview LOGIN PASSWORD :'interview_password';

GRANT CONNECT ON DATABASE devfield_dev TO devfield_auth, devfield_project, devfield_interview;
CREATE SCHEMA auth AUTHORIZATION devfield_auth;
CREATE SCHEMA project AUTHORIZATION devfield_project;
CREATE SCHEMA interview AUTHORIZATION devfield_interview;
SQL
