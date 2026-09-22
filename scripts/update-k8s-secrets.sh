#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

NAMESPACE=""
MANAGEMENT_NAMESPACE="ia-management-node"
CERT_NAME=""
CERT_DIR=""
CLIENT_P12_SECRET="federation-client-p12"
FEDERATION_CERT_SECRET="federation-cert"
FEDERATION_DEPLOYMENT=""
MANAGEMENT_NODE_CERT_SECRET="management-node-cert"
MANAGEMENT_NODE_DEPLOYMENT="management-node-api"
PATCH_MANAGEMENT_NODE=true
YES=0

usage() {
  cat <<EOF
Usage: ./update-k8s-secrets.sh --namespace <namespace> --certname <name> --certdir <dir> --federation-deployment <name> [options]

Required:
  --namespace <namespace>                 Kubernetes namespace for federation secrets
  --certname <name>                       Certificate base name, e.g. client-org1
  --certdir <dir>                         Directory containing the certificate files
  --federation-deployment <name>          Federation deployment to restart

Options:
  --yes                                   Continue after showing the plan
  --management-namespace <namespace>      Default: $MANAGEMENT_NAMESPACE
  --client-p12-secret <name>              Default: $CLIENT_P12_SECRET
  --federation-cert-secret <name>         Default: $FEDERATION_CERT_SECRET
  --management-node-cert-secret <name>    Default: $MANAGEMENT_NODE_CERT_SECRET
  --management-node-deployment <name>     Default: $MANAGEMENT_NODE_DEPLOYMENT
  --patch-management-node <true|false>    Default: true
  --help                                  Show this help

Example:
  ./update-k8s-secrets.sh --yes --namespace ia-federation --certname client-org1 --certdir client-org1 --federation-deployment federator-client-org1-client
EOF
}

fail_usage() {
  echo "$1"
  echo
  usage
  exit 1
}

confirm() {
  prompt="$1"
  printf '%s [yes/NO] ' "$prompt"
  read answer
  case "$answer" in
    yes)
      return 0
      ;;
    *)
      echo "Aborted."
      exit 1
      ;;
  esac
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --help|-h)
      usage
      exit 0
      ;;
    --yes)
      YES=1
      ;;
    --namespace)
      if [ "$#" -lt 2 ]; then
        fail_usage "--namespace needs a namespace"
      fi
      shift
      NAMESPACE="$1"
      ;;
    --management-namespace)
      if [ "$#" -lt 2 ]; then
        fail_usage "--management-namespace needs a namespace"
      fi
      shift
      MANAGEMENT_NAMESPACE="$1"
      ;;
    --certname)
      if [ "$#" -lt 2 ]; then
        fail_usage "--certname needs a certificate name"
      fi
      shift
      CERT_NAME="$1"
      ;;
    --certdir)
      if [ "$#" -lt 2 ]; then
        fail_usage "--certdir needs a directory"
      fi
      shift
      CERT_DIR="$1"
      ;;
    --client-p12-secret)
      if [ "$#" -lt 2 ]; then
        fail_usage "--client-p12-secret needs a secret name"
      fi
      shift
      CLIENT_P12_SECRET="$1"
      ;;
    --federation-cert-secret)
      if [ "$#" -lt 2 ]; then
        fail_usage "--federation-cert-secret needs a secret name"
      fi
      shift
      FEDERATION_CERT_SECRET="$1"
      ;;
    --federation-deployment)
      if [ "$#" -lt 2 ]; then
        fail_usage "--federation-deployment needs a deployment name"
      fi
      shift
      FEDERATION_DEPLOYMENT="$1"
      ;;
    --management-node-cert-secret)
      if [ "$#" -lt 2 ]; then
        fail_usage "--management-node-cert-secret needs a secret name"
      fi
      shift
      MANAGEMENT_NODE_CERT_SECRET="$1"
      ;;
    --management-node-deployment)
      if [ "$#" -lt 2 ]; then
        fail_usage "--management-node-deployment needs a deployment name"
      fi
      shift
      MANAGEMENT_NODE_DEPLOYMENT="$1"
      ;;
    --patch-management-node)
      if [ "$#" -lt 2 ]; then
        fail_usage "--patch-management-node needs true or false"
      fi
      shift
      case "$1" in
        true|false)
          PATCH_MANAGEMENT_NODE="$1"
          ;;
        *)
          fail_usage "--patch-management-node must be true or false"
          ;;
      esac
      ;;
    *)
      fail_usage "Unknown option: $1"
      ;;
  esac
  shift
done

if [ -z "$CERT_NAME" ]; then
  fail_usage "--certname needs a certificate name"
fi

if [ -z "$NAMESPACE" ]; then
  fail_usage "--namespace needs a namespace"
fi

if [ -z "$CERT_DIR" ]; then
  fail_usage "--certdir needs a directory"
fi

if [ -z "$FEDERATION_DEPLOYMENT" ]; then
  fail_usage "--federation-deployment needs a deployment name"
fi

CLIENT_P12="$CERT_DIR/$CERT_NAME.p12"
CLIENT_JKS="$CERT_DIR/$CERT_NAME-keystore.jks"
CLIENT_P12_KEY="$CERT_NAME.p12"
CLIENT_JKS_KEY="$CERT_NAME-keystore.jks"

test -f "$CLIENT_P12" || { echo "Missing $CLIENT_P12"; exit 1; }
test -f "$CLIENT_JKS" || { echo "Missing $CLIENT_JKS"; exit 1; }

show_plan() {
  echo "Target namespace: $NAMESPACE"
  echo "Management namespace: $MANAGEMENT_NAMESPACE"
  echo
  echo "This will patch these existing secret data keys:"
  echo "  secret/$CLIENT_P12_SECRET"
  echo "    data.$CLIENT_P12_KEY <- $CLIENT_P12"
  echo "  secret/$FEDERATION_CERT_SECRET"
  echo "    data.$CLIENT_JKS_KEY <- $CLIENT_JKS"
  if [ "$PATCH_MANAGEMENT_NODE" = "true" ]; then
    echo "  secret/$MANAGEMENT_NODE_CERT_SECRET -n $MANAGEMENT_NAMESPACE"
    echo "    data.$CLIENT_JKS_KEY <- $CLIENT_JKS"
  else
    echo "  management node secret patch: skipped"
  fi
  echo
  echo "Other keys in those secrets will be left unchanged."
}

show_plan

if [ "$YES" != "1" ]; then
  echo
  echo "Run again with --yes to apply."
  exit 1
fi

kubectl get secret "$CLIENT_P12_SECRET" -n "$NAMESPACE" >/dev/null
kubectl get secret "$FEDERATION_CERT_SECRET" -n "$NAMESPACE" >/dev/null
if [ "$PATCH_MANAGEMENT_NODE" = "true" ]; then
  kubectl get secret "$MANAGEMENT_NODE_CERT_SECRET" -n "$MANAGEMENT_NAMESPACE" >/dev/null
fi

CLIENT_P12_KEY_EXISTS="$(kubectl get secret "$CLIENT_P12_SECRET" -n "$NAMESPACE" -o "go-template={{if index .data \"$CLIENT_P12_KEY\"}}yes{{end}}")"
CLIENT_JKS_KEY_EXISTS="$(kubectl get secret "$FEDERATION_CERT_SECRET" -n "$NAMESPACE" -o "go-template={{if index .data \"$CLIENT_JKS_KEY\"}}yes{{end}}")"
if [ "$PATCH_MANAGEMENT_NODE" = "true" ]; then
  MANAGEMENT_CLIENT_JKS_KEY_EXISTS="$(kubectl get secret "$MANAGEMENT_NODE_CERT_SECRET" -n "$MANAGEMENT_NAMESPACE" -o "go-template={{if index .data \"$CLIENT_JKS_KEY\"}}yes{{end}}")"
fi

if [ "$CLIENT_P12_KEY_EXISTS" != "yes" ]; then
  echo "Missing data.$CLIENT_P12_KEY in secret/$CLIENT_P12_SECRET"
  exit 1
fi

if [ "$CLIENT_JKS_KEY_EXISTS" != "yes" ]; then
  echo "Missing data.$CLIENT_JKS_KEY in secret/$FEDERATION_CERT_SECRET"
  exit 1
fi

if [ "$PATCH_MANAGEMENT_NODE" = "true" ] && [ "$MANAGEMENT_CLIENT_JKS_KEY_EXISTS" != "yes" ]; then
  echo "Missing data.$CLIENT_JKS_KEY in secret/$MANAGEMENT_NODE_CERT_SECRET -n $MANAGEMENT_NAMESPACE"
  exit 1
fi

CLIENT_P12_B64="$(base64 < "$CLIENT_P12" | tr -d '\n')"
CLIENT_JKS_B64="$(base64 < "$CLIENT_JKS" | tr -d '\n')"

confirm "Patch secret/$CLIENT_P12_SECRET data.$CLIENT_P12_KEY?"
kubectl patch secret "$CLIENT_P12_SECRET" \
  -n "$NAMESPACE" \
  --type merge \
  -p "{\"data\":{\"$CLIENT_P12_KEY\":\"$CLIENT_P12_B64\"}}"

confirm "Patch secret/$FEDERATION_CERT_SECRET data.$CLIENT_JKS_KEY?"
kubectl patch secret "$FEDERATION_CERT_SECRET" \
  -n "$NAMESPACE" \
  --type merge \
  -p "{\"data\":{\"$CLIENT_JKS_KEY\":\"$CLIENT_JKS_B64\"}}"

if [ "$PATCH_MANAGEMENT_NODE" = "true" ]; then
  confirm "Patch secret/$MANAGEMENT_NODE_CERT_SECRET data.$CLIENT_JKS_KEY in namespace $MANAGEMENT_NAMESPACE?"
  kubectl patch secret "$MANAGEMENT_NODE_CERT_SECRET" \
    -n "$MANAGEMENT_NAMESPACE" \
    --type merge \
    -p "{\"data\":{\"$CLIENT_JKS_KEY\":\"$CLIENT_JKS_B64\"}}"
fi

echo "Updated Kubernetes secrets:"
echo "  $CLIENT_P12_SECRET -n $NAMESPACE"
echo "  $FEDERATION_CERT_SECRET -n $NAMESPACE"
if [ "$PATCH_MANAGEMENT_NODE" = "true" ]; then
  echo "  $MANAGEMENT_NODE_CERT_SECRET -n $MANAGEMENT_NAMESPACE"
fi

echo
echo "Restart deployments when ready:"
confirm "kubectl rollout restart deployment/$FEDERATION_DEPLOYMENT -n $NAMESPACE"
kubectl rollout restart deployment/"$FEDERATION_DEPLOYMENT" -n "$NAMESPACE"

if [ "$PATCH_MANAGEMENT_NODE" = "true" ]; then
  confirm "kubectl rollout restart deployment/$MANAGEMENT_NODE_DEPLOYMENT -n $MANAGEMENT_NAMESPACE"
  kubectl rollout restart deployment/"$MANAGEMENT_NODE_DEPLOYMENT" -n "$MANAGEMENT_NAMESPACE"
fi
