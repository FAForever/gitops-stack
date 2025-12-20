#!/bin/sh

cache_dir=$1
shift

chart=$1
chart_cache_dir="$cache_dir/$chart"

mkdir -p "$chart_cache_dir"

if [ -s "$chart_cache_dir/args" ] && cmp -s -- "$chart_cache_dir/args" <(echo -n "$@") && [ -s "$chart_cache_dir/files" ] && cmp -s -- "$chart_cache_dir/files" <(find "$chart" -type f -name "*.yaml" | sort) && [ -s "$chart_cache_dir/md5sum" ] && md5sum --status -c "$chart_cache_dir/md5sum" && [ -s "$chart_cache_dir/yaml" ]; then
    echo "$chart matches cached yaml"
    exit 0
fi

if helm dependency list "$chart" | grep -qE '.*missing\s*$'; then 
    helm dependency update "$chart"
fi

echo "Generating yaml from helm in $chart"
helm template "$@" > "$chart_cache_dir/yaml"

echo "Saving template files"
template_files=$(find "$chart" -type f -name "*.yaml" | sort)
echo "$template_files" > "$chart_cache_dir/files"

echo "Saving template sums"
find "$chart" -type f -name "*.yaml" -exec md5sum {} + > "$chart_cache_dir/md5sum"

for ARGUMENT in "$@"
do
    KEY=$(echo "$ARGUMENT" | cut -f1 -d=)
    VALUE=$(echo "$ARGUMENT" | cut -f2 -d=)

    if [[ "$KEY" == "--values" ]] && [[ "$VALUE" != "--values" ]]; then
        md5sum "$VALUE" >> "$chart_cache_dir/md5sum"
    fi

    if [[ $PREVIOUS_KEY == "--values" ]] && [[ "$PREVIOUS_VALUE" == "--values" ]]; then
        md5sum "$VALUE" >> "$chart_cache_dir/md5sum"
    fi

    PREVIOUS_KEY="$KEY"
    PREVIOUS_VALUE="$VALUE"
done

echo "Saving args"
echo -n "$@" > "$chart_cache_dir/args"