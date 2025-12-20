# This file is used for local development with tilt from https://tilt.dev/ in order to stand up the faf k8s stack from scratch

config.define_string("windows-bash-path", args=False, usage="Path to bash.exe for windows")
config.define_string("test-data-path", args=False, usage="Path to test data sql file")
config.define_string("lobby-server-path", args=False, usage="Path to lobby server repository")
config.define_string_list("to-run", args=True)
cfg = config.parse()
windows_bash_path = cfg.get("windows-bash-path", "C:\\Program Files\\Git\\bin\\bash.exe")

data_relative_path = ".local-data"
if os.name == "nt":
    if not os.path.exists(windows_bash_path):
        fail("Windows users need to supply a valid path to a bash executable")

    if k8s_context() == "docker-desktop":
        drive, path_without_drive = os.getcwd().split(":")
        data_absolute_path = os.path.join("//run/desktop/mnt/host/", drive, path_without_drive, data_relative_path).replace("\\","/").lower()
        use_named_volumes = ["mariadb"]
    elif k8s_context() == "minikube":
        data_absolute_path = "//data/" + data_relative_path
        use_named_volumes = []

else:
    data_absolute_path = os.path.join(os.getcwd(), data_relative_path)
    use_named_volumes = []

def as_windows_command(command):
    if type(command) == "list":
        return [windows_bash_path, "-c"] + [" ".join(command)]
    else:
        fail("Unknown command type")

def agnostic_local_resource(name, cmd, **kwargs):
    local_resource(name=name, cmd=cmd, cmd_bat=as_windows_command(cmd), **kwargs)

def agnostic_local(cmd):
    local(command=cmd, command_bat=as_windows_command(cmd))

def patch_config(yaml, config_name, config):
    objects = decode_yaml_stream(yaml)
    for object in objects:
        if object["kind"] == "ConfigMap" and object["metadata"]["name"] == config_name:
            object["data"].update(config)
    
    return encode_yaml_stream(objects)

def keep_objects_of_kind(yaml, kinds):
    objects = decode_yaml_stream(yaml)
    return encode_yaml_stream([object for object in objects if object["kind"] in kinds])

def remove_init_container(yaml):
    objects = decode_yaml_stream(yaml)
    for object in objects:
        if "spec" in object:
            spec = object["spec"]
            if "template" in spec:
                template = spec["template"]
                if "spec" in template and "initContainers" in template["spec"]:
                    template["spec"].pop("initContainers")

    return encode_yaml_stream(objects)

def cronjob_to_job(yaml):
    objects = decode_yaml_stream(yaml)
    for object in objects:
        if object["kind"] == "CronJob":
            object["kind"] = "Job"
            spec = object["spec"]
            spec.pop("suspend")
            spec.pop("schedule")
            jobTemplate = spec.pop("jobTemplate")
            spec["template"] = jobTemplate["spec"]["template"]

    return encode_yaml_stream(objects)

def helm_with_build_cache(chart, namespace="", values=[], set=[]):
    cache_dir = ".helm-cache"
    
    chart_resource = chart.replace("/", "-")
    chart_cache_path = os.path.join(cache_dir, chart)
    cached_yaml = os.path.join(chart_cache_path, "yaml")
    value_flags = [fragment for value in values for fragment in ("--values", value)]
    set_flags = [fragment for set_value in set for fragment in ("--set", set_value)]
    command = ["./tilt/scripts/helm-with-cache.sh", cache_dir, chart, "--include-crds"]
    if namespace:
        command.extend(["--namespace", namespace])
    command.extend(value_flags)
    command.extend(set_flags)
    
    deps = [chart]
    deps.extend(values)
    agnostic_local_resource(name=chart_resource + "-helm", cmd=command, labels=["helm"], deps=deps, allow_parallel=True)

    if not os.path.exists(cached_yaml):
        agnostic_local(command)

    objects = read_yaml_stream(cached_yaml)
    if not objects:
        agnostic_local(command)
        objects = read_yaml_stream(cached_yaml)

    watch_file(cached_yaml)

    if namespace:
        for object in objects:
            if "namespace" not in object["metadata"]:
                object["metadata"]["namespace"] = namespace 

    return encode_yaml_stream(objects)

def to_hostpath_storage(yaml, use_named_volumes):
    objects = decode_yaml_stream(yaml)
    for object in objects:
        if object["kind"] == "PersistentVolume":
            object["spec"].pop("nodeAffinity")
            localpath = object["spec"].pop("local")
            if os.path.basename(localpath["path"]) in use_named_volumes:
                volume_name = os.path.basename(localpath["path"])
                localpath["path"] = volume_name
            
            object["spec"]["hostPath"] = localpath
            object["spec"]["hostPath"]["type"] = "DirectoryOrCreate"
            object["spec"]["accessModes"] = ["ReadWriteMany"]
        if object["kind"] == "PersistentVolumeClaim":
            object["spec"]["accessModes"] = ["ReadWriteMany"]
            
    return encode_yaml_stream(objects)

def no_policy_server(yaml):
    objects = decode_yaml_stream(yaml)
    for object in objects:
        if object["kind"] == "ConfigMap" and object["metadata"]["name"] == "faf-lobby-server":
            config_yaml = object["data"]["config.yaml"]
            config_objects = decode_yaml(config_yaml)
            config_objects["USE_POLICY_SERVER"] = False
            config_yaml = encode_yaml(config_objects)
            object["data"]["config.yaml"] = str(config_yaml)
            
    return encode_yaml_stream(objects)

k8s_yaml("tilt/config/namespaces.yaml")
k8s_yaml(helm_with_build_cache("infra/clusterroles", namespace="faf-infra", values=["config/local.yaml"]))
k8s_resource(new_name="namespaces", objects=["faf-infra:namespace", "faf-apps:namespace", "faf-ops:namespace"], labels=["core"])
k8s_resource(new_name="clusterroles", objects=["read-cm-secrets:clusterrole"], labels=["core"])
k8s_resource(new_name="init-apps", objects=["init-apps:serviceaccount:faf-infra", "init-apps:serviceaccount:faf-apps", "allow-init-apps-read-app-config-infra:rolebinding", "allow-init-apps-read-app-config-apps:rolebinding"], resource_deps=["clusterroles"], labels=["core"])

storage_yaml = helm_with_build_cache("cluster/storage", values=["config/local.yaml"], set=["dataPath="+data_absolute_path])
storage_yaml = to_hostpath_storage(storage_yaml, use_named_volumes=use_named_volumes)
k8s_yaml(storage_yaml)

volume_identifiers = []
for object in decode_yaml_stream(storage_yaml):
    name = object["metadata"]["name"]
    kind = object["kind"].lower()
    volume_identifiers.append(name + ":" + kind)

k8s_resource(new_name="volumes", objects=volume_identifiers, labels=["core"], trigger_mode=TRIGGER_MODE_MANUAL)

traefik_yaml = helm_with_build_cache("cluster/traefik", values=["config/local.yaml"], namespace="traefik")
k8s_yaml(traefik_yaml)

traefik_identifiers = []
for object in decode_yaml_stream(traefik_yaml):
    name = object["metadata"]["name"]
    kind = object["kind"].lower()
    if kind != "deployment" and kind != "service":
        traefik_identifiers.append(name + ":" + kind)

k8s_resource(new_name="traefik-setup", objects=traefik_identifiers, labels=["traefik"])
k8s_resource(workload="release-name-traefik", new_name="traefik", port_forwards=["443:8443"], resource_deps=["traefik-setup"], labels=["traefik"])

postgres_yaml = helm_with_build_cache("infra/postgres", namespace="faf-infra", values=["config/local.yaml"])
postgres_init_user_yaml, postgres_resource_yaml = filter_yaml(postgres_yaml, {"app": "postgres-sync-db-user"})
k8s_yaml(postgres_init_user_yaml)
k8s_yaml(postgres_resource_yaml)
k8s_yaml(helm_with_build_cache("apps/faf-postgres", namespace="faf-apps", values=["config/local.yaml"]))
k8s_resource(workload="postgres", objects=["postgres:configmap", "postgres:secret", "postgres:service:faf-apps"], port_forwards=["5432"], resource_deps=["volumes"], labels=["database"])
postgres_setup_resources = []
for object in decode_yaml_stream(postgres_init_user_yaml):
    postgres_setup_resources.append(object["metadata"]["name"])
    k8s_resource(workload=object["metadata"]["name"], resource_deps=["init-apps", "postgres", "wikijs-config", "ory-hydra-config"], labels=["database"])

mariadb_yaml = helm_with_build_cache("infra/mariadb", namespace="faf-infra", values=["config/local.yaml"])
mariadb_init_user_yaml, mariadb_resource_yaml = filter_yaml(mariadb_yaml, {"app": "mariadb-sync-db-user"})
k8s_yaml(mariadb_init_user_yaml)
k8s_yaml(mariadb_resource_yaml)
k8s_yaml(helm_with_build_cache("apps/faf-mariadb", namespace="faf-apps", values=["config/local.yaml"]))
k8s_resource(workload="mariadb", objects=["mariadb:configmap", "mariadb:secret", "mariadb:service:faf-apps"], port_forwards=["3306"], resource_deps=["volumes"], labels=["database"])
mariadb_setup_resources = []
for object in decode_yaml_stream(mariadb_init_user_yaml):
    mariadb_setup_resources.append(object["metadata"]["name"])
    k8s_resource(workload=object["metadata"]["name"], resource_deps=["init-apps", "mariadb", "faf-api-config", "faf-user-service-config", "faf-lobby-server-config", "faf-replay-server-config", "faf-policy-server-config", "faf-league-service-config", "wordpress-config", "ergochat-config"], labels=["database"])

mongodb_yaml = helm_with_build_cache("infra/mongodb", namespace="faf-infra", values=["config/local.yaml"])
mongodb_init_user_yaml, mongodb_resource_yaml = filter_yaml(mongodb_yaml, {"app": "mongodb-sync-db-user"})
k8s_yaml(mongodb_init_user_yaml)
k8s_yaml(mongodb_resource_yaml)
k8s_yaml(helm_with_build_cache("apps/faf-mongodb", namespace="faf-apps", values=["config/local.yaml"]))
k8s_resource(workload="mongodb", objects=["mongodb:configmap", "mongodb:secret", "mongodb:service:faf-apps"], port_forwards=["27017"], resource_deps=["volumes"], labels=["database"])
mongodb_setup_resources = []
for object in decode_yaml_stream(mongodb_init_user_yaml):
    mongodb_setup_resources.append(object["metadata"]["name"])
    k8s_resource(workload=object["metadata"]["name"], resource_deps=["init-apps", "mongodb", "nodebb-config"], labels=["database"])

rabbitmq_yaml = helm_with_build_cache("apps/rabbitmq", namespace="faf-apps", values=["config/local.yaml"])
rabbitmq_init_user_yaml, rabbitmq_resource_yaml = filter_yaml(rabbitmq_yaml, {"app": "rabbitmq-sync-user"})
k8s_yaml(rabbitmq_init_user_yaml)
k8s_yaml(rabbitmq_resource_yaml)
k8s_resource(workload="rabbitmq", objects=["rabbitmq:configmap", "rabbitmq:secret"], port_forwards=["15672"], resource_deps=["volumes"], labels=["rabbitmq"])
rabbitmq_setup_resources = []
for object in decode_yaml_stream(rabbitmq_init_user_yaml):
    rabbitmq_setup_resources.append(object["metadata"]["name"])
    k8s_resource(workload=object["metadata"]["name"], resource_deps=["init-apps", "rabbitmq", "faf-api-config", "faf-icebreaker-config", "faf-lobby-server-config", "debezium-config", "faf-league-service-config"], labels=["rabbitmq"])

k8s_yaml(cronjob_to_job(helm_with_build_cache("apps/faf-db-migrations", namespace="faf-apps", values=["config/local.yaml"])))
k8s_resource(workload="faf-db-migrations", objects=["faf-db-migrations:secret"], resource_deps=mariadb_setup_resources, labels=["database"])

populate_db_command = ["tilt/scripts/populate-db.sh", cfg.get("test-data-path", "tilt/sql/test-data.sql")]
agnostic_local_resource(name = "populate-db", allow_parallel = True, cmd = populate_db_command, resource_deps=["faf-db-migrations"], labels=["database"], auto_init=False)

k8s_yaml(keep_objects_of_kind(helm_with_build_cache("apps/faf-voting", namespace="faf-apps", values=["config/local.yaml"]), kinds=["ConfigMap", "Secret"]))
k8s_resource(new_name="faf-voting-config", objects=["faf-voting:configmap", "faf-voting:secret"], labels=["voting"])

k8s_yaml(helm_with_build_cache("apps/faf-website", namespace="faf-apps", values=["config/local.yaml", "apps/faf-website/values-prod.yaml"]))
k8s_resource(new_name="faf-website-config", objects=["faf-website:configmap", "faf-website:secret"], labels=["website"])
k8s_resource(workload="faf-website", objects=["faf-website:ingressroute"], resource_deps=["traefik"], labels=["website"], links=[link("https://www.localhost", "FAForever Website")])

# k8s_yaml(helm_with_build_cache("apps/faf-content", namespace="faf-apps", values=["config/local.yaml"]))
# k8s_resource(new_name="faf-content-config", objects=["faf-content:configmap"], labels=["content"])
# k8s_resource(workload="faf-content", objects=["faf-content:ingressroute", "cors:middleware", "redirect-replay-subdomain:middleware"], resource_deps=["traefik"], labels=["content"], links=[link("https://content.localhost", "FAForever Content")])

k8s_yaml(helm_with_build_cache("apps/ergochat", namespace="faf-apps", values=["config/local.yaml"], set=["baseDomain=chat.localhost"]))
k8s_resource(new_name="ergochat-config", objects=["ergochat:configmap", "ergochat:secret"], labels=["chat"])
k8s_resource(workload="ergochat", objects=["ergochat-webirc:ingressroute"], resource_deps=["traefik"] + mariadb_setup_resources, port_forwards=["8097:8097"], labels=["chat"])

api_yaml = helm_with_build_cache("apps/faf-api", namespace="faf-apps", values=["config/local.yaml", "apps/faf-api/values-test.yaml"])
api_yaml = patch_config(api_yaml, "faf-api", {"JWT_FAF_HYDRA_ISSUER": "http://ory-hydra:4444"})
k8s_yaml(api_yaml)
k8s_resource(new_name="faf-api-config", objects=["faf-api:configmap", "faf-api:secret", "faf-api-mail:configmap"], labels=["api"])
k8s_resource(workload="faf-api", objects=["faf-api:ingressroute"], port_forwards=["8010"], resource_deps=["faf-api-config", "faf-db-migrations", "traefik", "ory-hydra"] + rabbitmq_setup_resources, labels=["api"], links=[link("https://api.localhost", "FAF API")])

k8s_yaml(helm_with_build_cache("apps/faf-league-service", namespace="faf-apps", values=["config/local.yaml"]))
k8s_resource(new_name="faf-league-service-config", objects=["faf-league-service:configmap", "faf-league-service:secret"], labels=["leagues"])
k8s_resource(workload="faf-league-service", resource_deps=["faf-league-service-config"] + mariadb_setup_resources + rabbitmq_setup_resources, labels=["leagues"])

lobby_server_yaml = helm_with_build_cache("apps/faf-lobby-server", namespace="faf-apps", values=["config/local.yaml"])
lobby_server_yaml = patch_config(lobby_server_yaml, "faf-lobby-server", {"HYDRA_JWKS_URI": "http://ory-hydra:4444/.well-known/jwks.json"})
lobby_server_yaml = no_policy_server(lobby_server_yaml)
k8s_yaml(lobby_server_yaml)
k8s_resource(new_name="faf-lobby-server-config", objects=["faf-lobby-server:configmap", "faf-lobby-server:secret"], labels=["lobby"])
k8s_resource(workload="faf-lobby-server", resource_deps=["faf-lobby-server-config", "faf-db-migrations", "ory-hydra"], labels=["lobby"])

k8s_yaml(keep_objects_of_kind(helm_with_build_cache("apps/faf-policy-server", namespace="faf-apps"), kinds=["ConfigMap", "Secret"]))
k8s_resource(new_name="faf-policy-server-config", objects=["faf-policy-server:configmap", "faf-policy-server:secret"], labels=["lobby"])

k8s_yaml(helm_with_build_cache("apps/faf-replay-server", namespace="faf-apps", values=["config/local.yaml"]))
k8s_resource(new_name="faf-replay-server-config", objects=["faf-replay-server:configmap", "faf-replay-server:secret"], labels=["replay"])
k8s_resource(workload="faf-replay-server", objects=["faf-replay-server:ingressroute", "faf-replay-server:ingressroutetcp"], port_forwards=["15001:15001"], resource_deps=["faf-replay-server-config", "faf-db-migrations", "traefik"], labels=["replay"])

user_service_yaml = helm_with_build_cache("apps/faf-user-service", namespace="faf-apps", values=["config/local.yaml"])
user_service_yaml = patch_config(user_service_yaml, "faf-user-service", {"HYDRA_TOKEN_ISSUER": "http://ory-hydra:4444", "HYDRA_JWKS_URL": "http://ory-hydra:4444/.well-known/jwks.json", "LOBBY_URL":"ws://localhost:8003", "REPLAY_URL":"ws://localhost:15001"})
k8s_yaml(user_service_yaml)
k8s_resource(new_name="faf-user-service-config", objects=["faf-user-service:configmap", "faf-user-service:secret", "faf-user-service-mail-templates:configmap"], labels=["user"])
k8s_resource(workload="faf-user-service", objects=["faf-user-service:ingressroute"], resource_deps=["faf-db-migrations", "traefik", "ory-hydra"], port_forwards=["8080"], labels=["user"], links=[link("https://user.localhost/register", "User Service Registration")])

k8s_yaml(helm_with_build_cache("apps/wordpress", namespace="faf-apps", values=["config/local.yaml"]))
k8s_resource(new_name="wordpress-config", objects=["wordpress:configmap", "wordpress:secret"], labels=["website"])
k8s_resource(workload="wordpress", objects=["wordpress:ingressroute"], resource_deps=["traefik"], labels=["website"], links=[link("https://direct.localhost", "FAF Wordpress")])

k8s_yaml(helm_with_build_cache("apps/wikijs", namespace="faf-apps", values=["config/local.yaml"]))
k8s_resource(new_name="wikijs-config", objects=["wikijs:configmap", "wikijs:secret", "wikijs-sso:configmap"], labels=["wiki"])
k8s_resource(workload="wikijs", objects=["wikijs:ingressroute"], resource_deps=["traefik"], labels=["wiki"], links=[link("https://wiki.localhost", "FAF Wiki")])

k8s_yaml(helm_with_build_cache("apps/nodebb", namespace="faf-apps", values=["config/local.yaml"]))
k8s_resource(new_name="nodebb-config", objects=["nodebb:configmap", "nodebb:secret"], labels=["forum"])
k8s_resource(workload="nodebb", objects=["nodebb:ingressroute"], port_forwards=["4567:4567"], resource_deps=["traefik"] + mongodb_setup_resources, labels=["forum"], links=[link("https://forum.localhost", "FAF Forum")])

k8s_yaml(helm_with_build_cache("apps/faf-unitdb", namespace="faf-apps", values=["config/local.yaml"]))
k8s_resource(new_name="faf-unitdb-config", objects=["faf-unitdb:configmap", "faf-unitdb:secret"], labels=["unitdb"])
k8s_resource(workload="faf-unitdb", objects=["faf-unitdb:ingressroute"], resource_deps=["traefik"], labels=["unitdb"], links=[link("https://unitdb.localhost", "Rackover UnitDB")])

k8s_yaml(keep_objects_of_kind(helm_with_build_cache("apps/debezium", namespace="faf-apps", values=["config/local.yaml"]), kinds=["ConfigMap", "Secret"]))
k8s_resource(new_name="debezium-config", objects=["debezium:configmap", "debezium:secret"], labels=["database"])

k8s_yaml(helm_with_build_cache("apps/faf-ws-bridge", namespace="faf-apps", values=["config/local.yaml"]))
k8s_resource(workload="faf-ws-bridge", objects=["faf-ws-bridge:ingressroute"], port_forwards=["8003"], resource_deps=["faf-lobby-server", "traefik"], labels=["lobby"])

icebreaker_yaml = helm_with_build_cache("apps/faf-icebreaker", namespace="faf-apps", values=["config/local.yaml"])
icebreaker_yaml = remove_init_container(icebreaker_yaml)
icebreaker_yaml = patch_config(icebreaker_yaml, "faf-icebreaker", {"HYDRA_URL": "http://ory-hydra:4444"})
k8s_yaml(icebreaker_yaml)
k8s_resource(new_name="faf-icebreaker-config", objects=["faf-icebreaker:configmap", "faf-icebreaker:secret"], labels=["api"])
k8s_resource(workload="faf-icebreaker", objects=["faf-icebreaker:ingressroute", "faf-icebreaker-stripprefix:middleware"], resource_deps=["faf-db-migrations", "traefik", "ory-hydra"] + rabbitmq_setup_resources, labels=["api"])

hydra_yaml = helm_with_build_cache("apps/ory-hydra", namespace="faf-apps", values=["config/local.yaml"])
hydra_client_create_yaml, hydra_resources_yaml = filter_yaml(hydra_yaml, {"app": "ory-hydra-create-clients"})
_, hydra_resources_yaml = filter_yaml(hydra_resources_yaml, {"app": "ory-hydra-janitor"})
hydra_resources_yaml = patch_config(hydra_resources_yaml, "ory-hydra", {"URLS_SELF_ISSUER": "http://ory-hydra:4444", "URLS_SELF_PUBLIC": "http://localhost:4444", "URLS_LOGIN": "http://localhost:8080/oauth2/login", "URLS_CONSENT": "http://localhost:8080/oauth2/consent", "DEV": "true"})
k8s_yaml(hydra_resources_yaml)
k8s_yaml(hydra_client_create_yaml)
k8s_resource(new_name="ory-hydra-config", objects=["ory-hydra:configmap", "ory-hydra:secret"], labels=["hydra"])
k8s_resource(workload="ory-hydra-migration", resource_deps=["ory-hydra-config"] + postgres_setup_resources, labels=["hydra"])
k8s_resource(workload="ory-hydra", objects=["ory-hydra:ingressroute"], resource_deps=["ory-hydra-migration", "traefik"], port_forwards=["4444", "4445"], labels=["hydra"])
for object in decode_yaml_stream(hydra_client_create_yaml):
    k8s_resource(workload=object["metadata"]["name"], resource_deps=["ory-hydra"], labels=["hydra"])