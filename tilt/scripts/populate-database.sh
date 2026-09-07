#!/bin/bash

curl -s https://raw.githubusercontent.com/FAForever/db/refs/heads/develop/test-data.sql | kubectl exec -i --namespace=faf-infra statefulset/mariadb -- mariadb --host=mariadb --user=root --password="banana" faf_lobby