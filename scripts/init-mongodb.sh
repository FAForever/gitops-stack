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

run_mongo_query() {
  kubectl -n $NAMESPACE exec -i mongodb-0 -- mongosh --quiet --username "$ADMIN_USER" --password "$ADMIN_PASSWORD" --authenticationDatabase admin --eval "$1"
}

# Function to check if a user exists
user_exists() {
    DATABASE=$1
    USERNAME=$2
    RESULT=$(run_mongo_query "db.getSiblingDB(\"$DATABASE\").getUser(\"$USERNAME\");")

    if [ "$RESULT" != "null" ]; then
        return 0  # User exists (true)
    else
        return 1  # User does not exist (false)
    fi
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
        run_mongo_query "db.getSiblingDB(\"${DB_NAME}\").createUser( { user: \"${DB_USER}\", pwd: \"${DB_PASSWORD}\", roles: [ \"readWrite\" ] } );"
        run_mongo_query "db.getSiblingDB(\"${DB_NAME}\").grantRolesToUser(\"${DB_NAME}\",[{ role: \"clusterMonitor\", db: \"admin\" }]);"
        echo "User $DB_USER created in db $DB_NAME."
    fi
}

create_user_and_db faf-apps nodebb MONGODB_USER MONGO_NODEBB_PASSWORD MONGODB_DATABASE

echo "All users and databases have been processed."
