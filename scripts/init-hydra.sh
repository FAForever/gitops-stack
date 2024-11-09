#!/bin/sh
# Setup rabbitmq vhost and users
export NAMESPACE="faf-apps"

# fail on errors
set -e

. ./k8s-helpers.sh

check_resource_exists_or_fail deployment ory-hydra2

hydra() {
  kubectl -n "faf-apps" exec deployment/ory-hydra2 -- hydra -e http://localhost:4445 "$@"
}

client_exists() {
  # Get the count of clients with the specified client name
  count=$(hydra list clients --format json-pretty | grep -c "\"client_name\": \"$1\"")

  if [ "$count" -gt 0 ]; then
    return 0  # Client exists
  else
    return 1  # Client does not exist
  fi
}

client_create() {
  CLIENT=$1

  if client_exists "$CLIENT"; then
      echo "OAuth2 client $CLIENT already exists. Skipping creation."
  else
    echo ""
    echo "--------------------"
    echo "Client: $CLIENT"
    echo "--------------------"
    hydra create oauth2-client \
        --name="$CLIENT" \
        "${@:2}"
    echo "--------------------"
  fi
}

client_create postman \
  --secret="banana" \
  --grant-type="authorization_code,refresh_token" \
  --response-type="code,id_token" \
  --scope="openid,email,offline" \
  --redirect-uri="http://127.0.0.1" \
  --token-endpoint-auth-method="none"

client_create "FAF Client" \
  --grant-type="authorization_code,refresh_token" \
  --scope="openid,email,offline,public_profile,lobby,upload_map,upload_mod" \
  --redirect-uri="http://127.0.0.1" \
  --logo-uri="https://faforever.com/images/faf-logo.png" \
  --tos-uri="https://faforever.com/tos" \
  --policy-uri="https://faforever.com/privacy" \
  --token-endpoint-auth-method="none"

client_create "FAF Moderator Client" \
  --grant-type="authorization_code" \
  --scope="openid,public_profile,upload_avatar,administrative_actions,read_sensible_userdata,manage_vault" \
  --redirect-uri="http://localhost,http://localhost:8080/,http://127.0.0.1" \
  --logo-uri="https://faforever.com/images/faf-logo.png" \
  --tos-uri="https://faforever.com/tos" \
  --policy-uri="https://faforever.com/privacy" \
  --client-uri="https://github.com/FAForever/faf-moderator-client" \
  --token-endpoint-auth-method="none"

client_create "FAF Classic Client (Python)" \
  --grant-type="authorization_code,refresh_token" \
  --scope="openid,offline,lobby,public_profile" \
  --redirect-uri="http://localhost,http://127.0.0.1" \
  --logo-uri="https://faforever.com/images/faf-logo.png" \
  --tos-uri="https://faforever.com/tos" \
  --policy-uri="https://faforever.com/privacy" \
  --client-uri="https://github.com/FAForever/faf-moderator-client" \
  --token-endpoint-auth-method="none"

client_create "Ethereal FAF client" \
  --grant-type="authorization_code,refresh_token" \
  --scope="openid,offline,public_profile,lobby,upload_map,upload_mod" \
  --redirect-uri="http://localhost,http://localhost:57728,http://localhost:59573,http://localhost:58256,http://localhost:53037,http://localhost:51360" \
  --logo-uri="https://raw.githubusercontent.com/Eternal-ll/Ethereal-FAF-Client/master/Logo/OAuth.svg" \
  --client-uri="https://github.com/Eternal-ll/Ethereal-FAF-Client" \
  --token-endpoint-auth-method="none"

check_resource_exists_or_fail secret faf-website
client_create "www.faforever.com" \
  --secret="$(get_secret_value faf-website OAUTH_CLIENT_SECRET)" \
  --skip-consent \
  --grant-type="authorization_code,refresh_token" \
  --scope="openid,offline,public_profile,write_account_data" \
  --redirect-uri="https://www.faforever.com/callback,https://www.faforever.com/auth" \
  --logo-uri="https://faforever.com/images/faf-logo.png" \
  --tos-uri="https://faforever.com/tos" \
  --policy-uri="https://faforever.com/privacy" \
  --client-uri="https://github.com/FAForever/faf-moderator-client" \
  --token-endpoint-auth-method="client_secret_post"

client_create "faf-website-public" \
  --secret="$(get_secret_value faf-website OAUTH_M2M_CLIENT_SECRET)" \
  --grant-type="client_credentials" \
  --scope="public_profile" \
  --token-endpoint-auth-method="client_secret_post"

check_resource_exists_or_fail secret faf-voting
client_create "voting.faforever.com" \
  --secret="$(get_secret_value faf-voting CLIENT_SECRET)" \
  --skip-consent \
  --grant-type="authorization_code" \
  --scope="openid,public_profile" \
  --redirect-uri="https://wiki.faforever.com/login/9edbd0f7-b647-46dc-97c9-3a20293cd830/callback" \
  --logo-uri="https://faforever.com/images/faf-logo.png" \
  --tos-uri="https://faforever.com/tos" \
  --policy-uri="https://faforever.com/privacy" \
  --token-endpoint-auth-method="client_secret_post"

check_resource_exists_or_fail secret nodebb
client_create "forum.faforever.com" \
  --secret="$(get_secret_value nodebb OAUTH_SECRET)" \
  --skip-consent \
  --grant-type="authorization_code,refresh_token" \
  --scope="openid,email,public_profile,lobby" \
  --redirect-uri="https://forum.faforever.com/auth/faf-nodebb/callback" \
  --logo-uri="https://faforever.com/images/faf-logo.png" \
  --tos-uri="https://faforever.com/tos" \
  --policy-uri="https://faforever.com/privacy" \
  --token-endpoint-auth-method="client_secret_post"

client_create "brackman-discord" \
  --secret="banana" \
  --grant-type="client_credentials" \
  --scope="public_profile" \
  --token-endpoint-auth-method="client_secret_post" \
  --owner="Paul Wayper"

client_create "faf-qai" \
  --secret="banana" \
  --grant-type="client_credentials" \
  --scope="public_profile" \
  --token-endpoint-auth-method="client_secret_post"
