#!/bin/sh
# Setup rabbitmq vhost and users
export NAMESPACE="faf-apps"

# fail on errors
set -e

. ./k8s-helpers.sh

check_resource_exists_or_fail secret rabbitmq
check_resource_exists_or_fail statefulset rabbitmq
check_resource_exists_or_fail pod rabbitmq-0

# create_vhost(vhost_name)
#
# Will create a blank vhost
create_vhost() {
  VHOST_NAME=$1
  kubectl -n $NAMESPACE exec -i rabbitmq-0 -- rabbitmqctl add_vhost "$VHOST_NAME"
}

# create_user_for_vhost(service_name, username_key, password_key, vhost_key)
#
# Will create a user (if not exists) with matching username and password taken from configMap and secret
# of the service name
create_user_for_vhost() {
  SERVICE_NAME=$1
  USERNAME=$(get_config_value "$SERVICE_NAME" "$2")
  PASSWORD=$(get_secret_value "$SERVICE_NAME" "$3")
  VHOST_NAME=$4

  echo "Creating RabbitMQ user $USERNAME for vhost $VHOST_NAME"
  # fuck you grep for your incapability to process hyphens
  USER_EXISTS=$(kubectl -n $NAMESPACE exec rabbitmq-0 -- rabbitmqctl list_users | sed -n "/$USERNAME/p" | wc -l)

  if [ "$USER_EXISTS" -eq 1 ]
  then
    echo "WARNING: User already exists. Will be recreated!"
    kubectl -n $NAMESPACE exec -i rabbitmq-0 -- rabbitmqctl delete_user "${USERNAME}"
  fi

  kubectl -n $NAMESPACE exec -i rabbitmq-0 -- rabbitmqctl add_user "${USERNAME}" "${PASSWORD}"
  kubectl -n $NAMESPACE exec -i rabbitmq-0 -- rabbitmqctl set_permissions -p "${VHOST_NAME}" "${USERNAME}" ".*" ".*" ".*"
}

make_user_admin() {
  SERVICE_NAME=$1
  USERNAME=$(get_config_value "$SERVICE_NAME" "$2")

  echo "Promoting RabbitMQ user $USERNAME to administrator"

  kubectl -n $NAMESPACE exec -i rabbitmq-0 -- rabbitmqctl set_user_tags "${USERNAME}" administrator
}

VHOST_FAF_CORE="/faf-core"

create_vhost $VHOST_FAF_CORE
create_user_for_vhost rabbitmq ADMIN_USER ADMIN_PASSWORD $VHOST_FAF_CORE
make_user_admin rabbitmq ADMIN_USER
create_user_for_vhost faf-lobby-server MQ_USER MQ_PASSWORD $VHOST_FAF_CORE
create_user_for_vhost faf-api RABBIT_USERNAME RABBIT_PASSWORD $VHOST_FAF_CORE
create_user_for_vhost faf-league-service MQ_USER MQ_PASSWORD $VHOST_FAF_CORE
create_user_for_vhost debezium RABBITMQ_USER RABBITMQ_PASSWORD $VHOST_FAF_CORE
create_user_for_vhost faf-icebreaker RABBITMQ_USER RABBITMQ_PASSWORD $VHOST_FAF_CORE

