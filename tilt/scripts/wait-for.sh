#!/bin/bash

until kubectl wait "$@" 2>/dev/null; do
  echo "Waiting for resources to be created and ready..."
  sleep 2
done