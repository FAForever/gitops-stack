#!/bin/sh
# Setup rabbitmq vhost and users
export NAMESPACE="faf-infra"

# fail on errors
set -e

. ./k8s-helpers.sh

check_resource_exists_or_fail secret mongodb
check_resource_exists_or_fail statefulset mongodb
check_resource_exists_or_fail pod mongodb-0

ADMIN_USER=$(get_config_value mongodb MONGO_INITDB_ROOT_USERNAME)
ADMIN_PASSWORD=$(get_secret_value mongodb MONGO_INITDB_ROOT_PASSWORD)

# Function to check if a user exists
user_exists() {
    DATABASE=$1
    USERNAME=$2
    kubectl -n $NAMESPACE exec -i mongodb-0 -- mongo --quiet --username "$ADMIN_USER" --password "$ADMIN_PASSWORD" --authenticationDatabase admin --eval "db.getSiblingDB(\"$DATABASE\").getUser(\"$USERNAME\");"
}

# Function to check if a database exists
database_exists() {
    DATABASE=$1
    kubectl -n $NAMESPACE exec -i mongodb-0 -- mongo --quiet --username "$ADMIN_USER" --password "$ADMIN_PASSWORD" --authenticationDatabase admin --eval "db.getMongo().getDBs().databases.some(db => db.name == \"$DATABASE\");"

}

create_user_and_db() {
    SERVICE_NAMESPACE=$1
    SERVICE_NAME=$2
    DB_USER=$(NAMESPACE=$SERVICE_NAMESPACE get_config_value "$SERVICE_NAME" "$3")
    DB_PASSWORD=$(NAMESPACE=$SERVICE_NAMESPACE get_secret_value "$SERVICE_NAME" "$4")
    DB_NAME=$(NAMESPACE=$SERVICE_NAMESPACE get_config_value "$SERVICE_NAME" "$5")

    # Create user if it does not exist
    if user_exists "$DB_NAME" "$DB_USER"; then
        echo "User $DB_USER already exists in db $DB_NAME. Skipping user creation."
    else
        kubectl -n $NAMESPACE exec -i mongodb-0 -- psql --username=mongodb -c "CREATE USER \"$DB_USER\" WITH PASSWORD '$DB_PASSWORD';"
        echo "User $DB_USER created in db $DB_NAME."
    fi

    # Create database if it does not exist
    if database_exists "$DB_NAME"; then
        echo "Database $DB_NAME already exists. Skipping database creation."
    else
        kubectl -n $NAMESPACE exec -i mongodb-0 -- psql --username=mongodb -c "CREATE DATABASE \"$DB_NAME\" OWNER \"$DB_USER\";"
        echo "Database $DB_NAME created."
    fi

    # Grant all privileges on the database to the user
    kubectl -n $NAMESPACE exec -i mongodb-0 -- psql --username=mongodb -c "GRANT ALL PRIVILEGES ON DATABASE \"$DB_NAME\" TO \"$DB_USER\";"
    echo "Granted all privileges on database $DB_NAME to user $DB_USER."
}

create_user_and_db faf-apps wikijs DB_USER DB_PASS DB_NAME

echo "All users and databases have been processed."
