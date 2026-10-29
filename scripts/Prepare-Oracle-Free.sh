#!/usr/bin/env bash
# Run on the Linux VPS, before creating containers with compose.oracle-free.yaml.
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo 'Execute com sudo bash scripts/Prepare-Oracle-Free.sh na VPS.' >&2
  exit 1
fi
if [[ ! -d /run/systemd/system || ! -f /sys/fs/cgroup/cgroup.controllers ]]; then
  echo 'Este perfil exige Linux com systemd e cgroup v2 (por exemplo Ubuntu 24.04).' >&2
  exit 1
fi
if [[ $(docker info --format '{{.CgroupDriver}}') != systemd ]]; then
  echo 'O Docker precisa usar o cgroup driver systemd. Nenhuma configuração do Docker foi alterada.' >&2
  exit 1
fi
oracle_script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
install -m 0644 "$oracle_script_dir/../docker/oracle/ayla.slice" /etc/systemd/system/ayla.slice
systemctl daemon-reload
systemctl enable --now ayla.slice
# Also update limits if the slice was already running.
systemctl set-property --runtime ayla.slice CPUQuota=180% MemoryHigh=9G MemoryMax=10G MemorySwapMax=0
echo 'Orçamento instalado: 1,8 CPU / 10 GiB para os containers da Ayla.'
echo 'Agora use compose.oracle-free.yaml como o último arquivo Compose.'
