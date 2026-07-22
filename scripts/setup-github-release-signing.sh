#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

repository=Nulifyer/Archphene
keystore_path=tooling/signing/archphene-release.keystore
credentials_path=tooling/signing/archphene-release-credentials.json
key_alias=archphene
while (($#)); do
  case "$1" in
    --repository) repository="${2:?missing value for --repository}"; shift 2 ;;
    --keystore-path) keystore_path="${2:?missing value for --keystore-path}"; shift 2 ;;
    --credentials-path) credentials_path="${2:?missing value for --credentials-path}"; shift 2 ;;
    --key-alias) key_alias="${2:?missing value for --key-alias}"; shift 2 ;;
    -h|--help) echo "usage: $0 [--repository OWNER/REPO] [--keystore-path PATH] [--credentials-path PATH] [--key-alias ALIAS]"; exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
for command in keytool openssl jq gh base64; do archphene_require_command "$command"; done
[[ "$keystore_path" == /* ]] || keystore_path="$ARCHPHENE_ROOT/$keystore_path"
[[ "$credentials_path" == /* ]] || credentials_path="$ARCHPHENE_ROOT/$credentials_path"
mkdir -p "$(dirname "$keystore_path")" "$(dirname "$credentials_path")"
if [[ -e "$keystore_path" && ! -e "$credentials_path" ]] || [[ ! -e "$keystore_path" && -e "$credentials_path" ]]; then
  archphene_die "keystore and credentials backup must either both exist or both be absent"
fi
if [[ ! -e "$keystore_path" ]]; then
  password="$(openssl rand -base64 36 | tr -d '=\n')"
  keytool -genkeypair -noprompt -v -keystore "$keystore_path" -storetype PKCS12 \
    -storepass "$password" -keypass "$password" -alias "$key_alias" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=Archphene Release,O=Archphene,C=US"
  jq -n --arg password "$password" --arg alias "$key_alias" \
    '{storePassword:$password,keyPassword:$password,keyAlias:$alias}' > "$credentials_path"
fi
store_password="$(jq -er .storePassword "$credentials_path")"
key_password="$(jq -er .keyPassword "$credentials_path")"
key_alias="$(jq -er .keyAlias "$credentials_path")"
env -u ALL_PROXY -u HTTP_PROXY -u HTTPS_PROXY -u GIT_HTTP_PROXY -u GIT_HTTPS_PROXY gh api user --silent
set_secret() { printf '%s' "$2" | gh secret set "$1" --repo "$repository" --body -; }
set_secret ARCHPHENE_RELEASE_KEYSTORE_BASE64 "$(base64 -w0 "$keystore_path")"
set_secret ARCHPHENE_RELEASE_STORE_PASSWORD "$store_password"
set_secret ARCHPHENE_RELEASE_KEY_ALIAS "$key_alias"
set_secret ARCHPHENE_RELEASE_KEY_PASSWORD "$key_password"
archphene_note "GitHub release signing configured for $repository."
archphene_note "Back up both files outside this computer:"
archphene_note "  $keystore_path"
archphene_note "  $credentials_path"

