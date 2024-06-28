#!/bin/sh

printf "Supported:\n"
printf "[a]rgo-cd\n"
printf "[t]raefik\n"
printf "[r]abbitmq\n"
printf "[m]ariadb [port]\n"
printf "\n"

# Check if an argument is given, if not, read from input
if [ $# -eq 0 ]; then
  printf "Enter the application: "
  read type
else
  type=$1
fi

case "$type" in
  a)
    echo "Access argo-cd on http://localhost:8080"
    kubectl -n argocd port-forward service/argocd-server 8080:80
    ;;
  t)
    echo "Access traefik on http://localhost:9000/dashboard/"
    kubectl -n traefik port-forward daemonset/traefik 9000:9000
    ;;
  r)
    echo "Access rabbitmq on http://localhost:15672"
    kubectl -n faf-apps port-forward statefulset/rabbitmq 15672:15672
    ;;
  m)
    if [ $# -eq 2 ]; then
      targetPort=$2
    else
      targetPort=3306
    fi
    echo "Access rabbitmq on port $targetPort"
    kubectl -n faf-apps port-forward statefulset/mariadb $targetPort:3306
    ;;
  *)
    echo "unknown command"
    ;;
esac
