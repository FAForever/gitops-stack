#!/bin/sh

statements=$(cat $1)
kubectl exec --namespace faf-infra statefulset/mariadb -- mariadb --user=root --password=banana faf_lobby --execute="$statements"