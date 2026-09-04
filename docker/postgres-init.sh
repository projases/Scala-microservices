#!/bin/bash
set -e

# The compose service uses POSTGRES_USER/POSTGRES_PASSWORD (demo/demo) as the
# bootstrapping superuser. Create one database per Scala service so each keeps
# its own schema and Flyway migration history.

for db in course users credential; do
  echo "Creating database: $db"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -c "CREATE DATABASE $db OWNER $POSTGRES_USER;"
done
