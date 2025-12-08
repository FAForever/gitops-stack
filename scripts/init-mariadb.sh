#!/bin/sh
# Setup rabbitmq vhost and users
export NAMESPACE="faf-infra"

# fail on errors
set -e

. ./k8s-helpers.sh

check_resource_exists_or_fail secret postgres
check_resource_exists_or_fail statefulset postgres
check_resource_exists_or_fail pod mariadb-0

MYSQL_ROOT_PASSWORD=$(get_secret_value "mariadb" "MARIADB_ROOT_PASSWORD")

create_user_and_db() {
    SERVICE_NAMESPACE=$1
    SERVICE_NAME=$2
    DB_USER=$(NAMESPACE=$SERVICE_NAMESPACE get_config_value "$SERVICE_NAME" "$3")
    DB_PASSWORD=$(NAMESPACE=$SERVICE_NAMESPACE get_secret_value "$SERVICE_NAME" "$4")
    DB_NAME=$(NAMESPACE=$SERVICE_NAMESPACE get_config_value "$SERVICE_NAME" "$5")
    DB_OPTIONS=${6:-}

    kubectl -n $NAMESPACE exec -i mariadb-0 -- mariadb --user=root --password=${MYSQL_ROOT_PASSWORD} <<SQL_SCRIPT
      CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` ${DB_OPTIONS};
      CREATE USER IF NOT EXISTS '${DB_USER}'@'%' IDENTIFIED BY '${DB_PASSWORD}';
      GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'%';
SQL_SCRIPT
    echo "User $DB_USER created."
}

# faf_lobby database
create_user_and_db faf-apps faf-api DATABASE_USERNAME DATABASE_PASSWORD DATABASE_NAME
create_user_and_db faf-apps faf-user-service DB_USERNAME DB_PASSWORD DB_DATABASE
create_user_and_db faf-apps faf-lobby-server DB_LOGIN DB_PASSWORD DB_NAME
create_user_and_db faf-apps faf-replay-server RS_DB_USERNAME RS_DB_PASSWORD RS_DB_DATABASE
create_user_and_db faf-apps faf-policy-server DATABASE_USER DATABASE_PASSWORD DATABASE_NAME

# league database
create_user_and_db faf-apps faf-api LEAGUE_DATABASE_USERNAME LEAGUE_DATABASE_PASSWORD LEAGUE_DATABASE_NAME
create_user_and_db faf-apps faf-league-service DB_LOGIN DB_PASSWORD DB_NAME

# others
create_user_and_db faf-apps faf-icebreaker DB_USERNAME DB_PASSWORD DB_USERNAME
create_user_and_db faf-apps wordpress WORDPRESS_DB_USER WORDPRESS_DB_PASSWORD WORDPRESS_DB_NAME
create_user_and_db faf-apps ergochat ERGO__DATASTORE__MYSQL__USER ERGO__DATASTORE__MYSQL__PASSWORD ERGO__DATASTORE__MYSQL__HISTORY_DATABASE

echo "All users and databases have been processed."
