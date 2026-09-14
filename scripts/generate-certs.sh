#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

CA_CERT="client-rootCA.crt"
CA_KEY="client-rootCA.key"
NAME=""
PASSWORD=""
DAYS="365"
SUBJECT="/C=UK/ST=London/L=London/O=ORG_1/OU=IT/CN=ORG_1/emailAddress=info@org1.com"
KEYTOOL_IMAGE="eclipse-temurin:21-jdk"
ROOT_PASSWORD_FILE=""
EXT_FILE="localhost.ext"

YES=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --yes)
      YES=1
      ;;
    --cert)
      if [ "$#" -lt 2 ]; then
        echo "--cert needs a certificate name"
        exit 1
      fi
      shift
      NAME="$1"
      ;;
    --password)
      if [ "$#" -lt 2 ]; then
        echo "--password needs a password"
        exit 1
      fi
      shift
      PASSWORD="$1"
      ;;
    --days)
      if [ "$#" -lt 2 ]; then
        echo "--days needs a number of days"
        exit 1
      fi
      shift
      DAYS="$1"
      ;;
    --subject)
      if [ "$#" -lt 2 ]; then
        echo "--subject needs a certificate subject"
        exit 1
      fi
      shift
      SUBJECT="$1"
      ;;
    --keytool-image)
      if [ "$#" -lt 2 ]; then
        echo "--keytool-image needs a container image"
        exit 1
      fi
      shift
      KEYTOOL_IMAGE="$1"
      ;;
    --rootpasswdfile)
      if [ "$#" -lt 2 ]; then
        echo "--rootpasswdfile needs a password file"
        exit 1
      fi
      shift
      ROOT_PASSWORD_FILE="$1"
      ;;
    --extfile)
      if [ "$#" -lt 2 ]; then
        echo "--extfile needs an extension file"
        exit 1
      fi
      shift
      EXT_FILE="$1"
      ;;
    *)
      echo "Unknown option: $1"
      echo "Run: ./generate-certs.sh --yes --cert client-org1 --password changeit --rootpasswdfile file [--days 365] [--subject subject] [--extfile localhost.ext] [--keytool-image image]"
      exit 1
      ;;
  esac
  shift
done

if [ -z "$NAME" ]; then
  echo "--cert needs a certificate name"
  exit 1
fi

if [ -z "$PASSWORD" ]; then
  echo "--password needs a password"
  exit 1
fi

if [ -z "$ROOT_PASSWORD_FILE" ]; then
  echo "--rootpasswdfile needs a password file"
  exit 1
fi

OUT_DIR="$NAME"
KEY="$OUT_DIR/$NAME.key"
CSR="$OUT_DIR/$NAME.csr"
CRT="$OUT_DIR/$NAME.crt"
SERIAL="$OUT_DIR/$NAME.srl"
P12="$OUT_DIR/$NAME.p12"
CLIENT_P12="$OUT_DIR/client.p12"
KEYSTORE="$OUT_DIR/$NAME-keystore.jks"

if [ "$YES" != "1" ]; then
  echo "This will overwrite files in $OUT_DIR/: $NAME.key, $NAME.csr, $NAME.crt, $NAME.p12, client.p12, and maybe $NAME-keystore.jks."
  echo "Run: ./generate-certs.sh --yes --cert client-org1 --password changeit --rootpasswdfile file [--days 365] [--subject subject] [--extfile localhost.ext] [--keytool-image image]"
  exit 1
fi

test -f "$CA_CERT" || { echo "Missing $CA_CERT"; exit 1; }
test -f "$CA_KEY" || { echo "Missing $CA_KEY"; exit 1; }
test -f "$ROOT_PASSWORD_FILE" || { echo "Missing $ROOT_PASSWORD_FILE"; exit 1; }
test -f "$EXT_FILE" || { echo "Missing $EXT_FILE"; exit 1; }
openssl pkey -in "$CA_KEY" -passin "file:$ROOT_PASSWORD_FILE" -noout >/dev/null 2>&1 || {
  echo "Could not read CA private key with password from $ROOT_PASSWORD_FILE"
  exit 1
}
mkdir -p "$OUT_DIR"

sign_certificate() {
  openssl x509 -req \
    -in "$CSR" \
    -CA "$CA_CERT" \
    -CAkey "$CA_KEY" \
    -passin "file:$ROOT_PASSWORD_FILE" \
    -CAserial "$SERIAL" \
    -CAcreateserial \
    -out "$CRT" \
    -days "$DAYS" \
    -sha256 \
    -extfile "$EXT_FILE" \
    -extensions v3_req
}

openssl genrsa -out "$KEY" 4096
openssl req -new -key "$KEY" -out "$CSR" -subj "$SUBJECT"
sign_certificate

openssl pkcs12 -export \
  -inkey "$KEY" \
  -in "$CRT" \
  -certfile "$CA_CERT" \
  -name "$NAME" \
  -out "$P12" \
  -passout "pass:$PASSWORD"

cp "$P12" "$CLIENT_P12"

run_keytool() {
  if command -v keytool >/dev/null 2>&1; then
    keytool "$@"
  elif command -v docker >/dev/null 2>&1; then
    docker run --rm \
      -v "$PWD:/certs" \
      -w /certs \
      "$KEYTOOL_IMAGE" \
      keytool "$@"
  else
    echo "keytool and docker are both missing; skipped $KEYSTORE"
    return 0
  fi
}

run_keytool -importkeystore \
    -srckeystore "$P12" \
    -srcstoretype PKCS12 \
    -srcstorepass "$PASSWORD" \
    -srcalias "$NAME" \
    -destkeystore "$KEYSTORE" \
    -deststoretype JKS \
    -deststorepass "$PASSWORD" \
    -destkeypass "$PASSWORD" \
    -destalias "$NAME" \
    -noprompt

openssl verify -CAfile "$CA_CERT" "$CRT"
openssl x509 -in "$CRT" -noout -subject -issuer -dates
