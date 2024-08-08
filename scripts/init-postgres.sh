#!/bin/sh
# Setup rabbitmq vhost and users
export NAMESPACE="postgres"

# fail on errors
set -e

. ./k8s-helpers.sh

check_resource_exists_or_fail secret postgres
check_resource_exists_or_fail statefulset postgres
check_resource_exists_or_fail pod postgres-0

# Function to check if a user exists
user_exists() {
    kubectl -n $NAMESPACE exec -i postgres-0 -- psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='$1'" | grep -q 1
}

# Function to check if a database exists
database_exists() {
    kubectl -n $NAMESPACE exec -i postgres-0 -- psql -tAc "SELECT 1 FROM pg_database WHERE datname='$1'" | grep -q 1
}

create_user_and_db() {
    SERVICE_NAMESPACE=$1
    SERVICE_NAME=$2
    DB_USER=$(NAMESPACE=$SERVICE_NAMESPACE get_config_value "$SERVICE_NAME" "$3")
    DB_PASSWORD=$(NAMESPACE=$SERVICE_NAMESPACE get_secret_value "$SERVICE_NAME" "$4")
    DB_NAME=$(NAMESPACE=$SERVICE_NAMESPACE get_config_value "$SERVICE_NAME" "$5")

    # Create user if it does not exist
    if user_exists "$DB_USER"; then
        echo "User $DB_USER already exists. Skipping user creation."
    else
        kubectl -n $NAMESPACE exec -i postgres-0 -- psql -c "CREATE USER $DB_USER WITH PASSWORD '$DB_PASSWORD';"
        echo "User $DB_USER created."
    fi

    # Create database if it does not exist
    if database_exists "$DB_NAME"; then
        echo "Database $DB_NAME already exists. Skipping database creation."
    else
        kubectl -n $NAMESPACE exec -i postgres-0 -- psql -c "CREATE DATABASE $DB_NAME OWNER $DB_USER;"
        echo "Database $DB_NAME created."
    fi

    # Grant all privileges on the database to the user
    kubectl -n $NAMESPACE exec -i postgres-0 -- psql -c "GRANT ALL PRIVILEGES ON DATABASE $DB_NAME TO $DB_USER;"
    echo "Granted all privileges on database $DB_NAME to user $DB_USER."
}

create_user_and_db faf-apps faf-api DATABASE_USERNAME DATABASE_PASSWORD DATABASE_NAME

echo "All users and databases have been processed."
