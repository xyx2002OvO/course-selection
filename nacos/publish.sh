#!/bin/sh
set -eu
nacos="${NACOS_ADDR:-nacos:8848}"
publish() {
  dataId="$1"
  file="$2"
  curl -fsS -X POST "http://${nacos}/nacos/v1/cs/configs" \
    --data-urlencode "dataId=${dataId}" \
    --data-urlencode "group=DEFAULT_GROUP" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content@${file}"
}
publish selection-domain.yaml /cfg/selection-domain.yaml
publish selection-web.yaml /cfg/selection-web.yaml
