#!/bin/bash

faf_data_dir=$1
init_file="$faf_data_dir/bin/init_faf.lua"
init_file_md5sum=($(md5sum $init_file))

mariadb_exec() {
    kubectl exec --namespace=faf-infra statefulset/mariadb -- mariadb --host=mariadb --user=root --password="banana" faf_lobby  "$@"
}

mariadb_exec -e "TRUNCATE updates_faf_files;"
mariadb_exec -e "TRUNCATE updates_faf;"
mariadb_exec -e  "INSERT INTO updates_faf (id, filename, path) values (1, \"init_faf.lua\", \"bin\");"
mariadb_exec -e  "INSERT INTO updates_faf_files (fileId, version, name, md5) values (1, 1, \"init_faf_1.lua\", \"$init_file_md5sum\");"