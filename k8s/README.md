# PayFlux on Kubernetes (EKS demo)

The default Kustomize bundle runs the six Spring services and Next.js frontend in the `payflux` namespace, using the managed RDS, ElastiCache, and MSK data layer described below. Only the ALB Ingress is internet-facing; application Services (including `api-gateway` and `frontend`) are `ClusterIP`. Local PostgreSQL and Kafka manifests remain available for a separate demo deployment, but are not part of the default bundle.

> Demo only: the single-replica database and Kafka setup is not highly available. Use managed RDS/MSK or production-grade charts and backups for real workloads. Kubernetes Secrets are base64-encoded, not encrypted at rest by default; enable EKS secret encryption and use an external secret manager in production.

## Prerequisites

- An EKS cluster, `kubectl` context pointed to it, and nodes with capacity for the resource requests in these manifests.
- The Terraform add-ons in `../terraform/addons` applied after the EKS cluster exists. They install the EBS CSI EKS add-on, an encrypted default `gp3` StorageClass, the IRSA-backed AWS Load Balancer Controller, `metrics-server`, and the `payflux`/`monitoring` namespaces. Public subnets in the cluster VPC need the `kubernetes.io/role/elb=1` tag for this internet-facing ALB; the infrastructure Terraform already tags them.
- Docker, AWS CLI/ECR access, and a registry reachable by EKS nodes.

## Managed data layer (RDS, ElastiCache, MSK)

`terraform/infrastructure` also provisions private RDS PostgreSQL, ElastiCache Redis, and a two-broker MSK cluster. Set `TF_VAR_postgres_password` to a strong 16–128 character password before applying; Terraform marks it sensitive, but it is still stored in Terraform state, so protect the S3 backend and never commit the value. The RDS password cannot contain `/`, `@`, a quote, or a space. These demo-sized resources are single-AZ/non-HA where applicable and incur AWS charges while running.

```powershell
$env:TF_VAR_postgres_password = Read-Host "RDS password (16-128 characters)"
terraform -chdir=terraform/infrastructure init
terraform -chdir=terraform/infrastructure apply
$postgresHost = terraform -chdir=terraform/infrastructure output -raw postgres_host
$redisHost = terraform -chdir=terraform/infrastructure output -raw redis_host
$kafkaBrokers = terraform -chdir=terraform/infrastructure output -raw kafka_bootstrap_brokers
$dbPassword = terraform -chdir=terraform/infrastructure output -raw postgres_password
Remove-Item Env:TF_VAR_postgres_password
```

Update `config.yaml` with the three Terraform endpoints (`DB_URL` must be `jdbc:postgresql://<RDS-host>:5432/payflux`), then apply it. Create the Kubernetes Secret without putting the password in a manifest or shell history; add the application JWT secret as usual:

```powershell
# Edit config.yaml: POSTGRES_HOST, DB_URL, REDIS_HOST, KAFKA_BOOTSTRAP_SERVERS
kubectl apply -f .\k8s\namespace.yaml
kubectl apply -f .\k8s\config.yaml
$jwtSecret = Read-Host "JWT secret (at least 32 random bytes)"
kubectl create secret generic payflux-secrets -n payflux `
  --from-literal=DB_USERNAME=payflux `
  "--from-literal=DB_PASSWORD=$dbPassword" `
  "--from-literal=JWT_SECRET=$jwtSecret" `
  --dry-run=client -o yaml | kubectl apply -f -
Remove-Variable dbPassword, jwtSecret

kubectl apply -f .\k8s\data-layer-smoke-test.yaml
kubectl wait --for=jsonpath='{.status.phase}'=Running pod/data-layer-smoke-test -n payflux --timeout=10m
kubectl logs -n payflux data-layer-smoke-test -c postgres
kubectl logs -n payflux data-layer-smoke-test -c redis
kubectl logs -n payflux data-layer-smoke-test -c kafka
```

Use the Terraform `postgres_username` output (currently `payflux`) for
`DB_USERNAME` in the Secret. The infrastructure root requires Terraform state
storage and locking to exist first; bootstrap it once with:

```powershell
terraform -chdir=terraform/backend init
terraform -chdir=terraform/backend apply
```

Each log should report its connection/operation succeeded. The temporary pod checks PostgreSQL with TLS and `SELECT 1`, Redis with `PING`/`SET`/`GET`, and MSK with a metadata request and topic creation. Remove it and its test topic when done:

```powershell
kubectl delete -f .\k8s\data-layer-smoke-test.yaml
```

The default `kustomization.yaml` intentionally excludes `database.yaml` and `messaging.yaml` (the local in-cluster demo database and Kafka) and deploys no in-cluster Redis; its gateway/frontend resources are in `edge.yaml`. Configure `config.yaml` with the managed endpoints before applying it. MSK and ElastiCache currently use private PLAINTEXT connections to remain compatible with the existing clients; keep them private to the VPC and add TLS/SASL client configuration before using this beyond a connectivity demo. `terraform destroy` removes the managed services; the demo RDS configuration skips a final snapshot.

## Provision EKS and its add-ons

Terraform is split into two roots because Kubernetes and Helm providers require a reachable EKS API: create the cluster first, then install cluster add-ons.

```powershell
terraform -chdir=terraform/infrastructure init
terraform -chdir=terraform/infrastructure apply

aws eks update-kubeconfig --name payflux-cluster --region us-east-1
kubectl config current-context

terraform -chdir=terraform/addons init
terraform -chdir=terraform/addons apply
```

Verify add-on pods and test dynamic EBS provisioning:

```powershell
kubectl get pods -A
kubectl apply -f .\k8s\ebs-pvc-smoke-test.yaml
kubectl wait --for=jsonpath='{.status.phase}'=Bound pvc/ebs-smoke-test -n payflux --timeout=5m
kubectl wait --for=condition=Ready pod/ebs-smoke-test -n payflux --timeout=5m
kubectl exec -n payflux ebs-smoke-test -- cat /data/health
```

The smoke test writes `ebs-ok` to the mounted EBS volume. Clean it up with `kubectl delete -f .\k8s\ebs-pvc-smoke-test.yaml` when finished. Do not run these cluster commands until the current context is the intended EKS cluster.

## Build and push images

The Terraform in `../terraform/infrastructure` creates ECR repositories named `payflux-api-gateway`, `payflux-user-service`, `payflux-wallet-service`, `payflux-transaction-service`, `payflux-notification-service`, `payflux-reward-service`, and `payflux-frontend`. Authenticate to ECR, then build and push (replace `<account>` and `<region>`):

```powershell
$registry = "<account>.dkr.ecr.<region>.amazonaws.com"
aws ecr get-login-password --region <region> | docker login --username AWS --password-stdin $registry

$services = @("api-gateway", "user-service", "wallet-service", "transaction-service", "notification-service", "reward-service")
foreach ($service in $services) {
  docker build -t "$registry/payflux-$service:demo" ".\$service"
  docker push "$registry/payflux-$service:demo"
}

# API_INTERNAL_URL is baked into next.config.js during the Next.js build.
docker build --build-arg API_INTERNAL_URL=http://api-gateway:8080 `
  -t "$registry/payflux-frontend:demo" .\fronted_new
docker push "$registry/payflux-frontend:demo"
```

`kustomization.yaml` maps the workload image aliases to the ECR repositories created by this project. Its `newTag` values default to `demo`; the tags must exist in ECR before applying. The GitHub Actions workflow publishes all seven app images with the commit SHA when any app service changes, then substitutes that SHA for the `demo` tags and runs `kubectl apply -k ./k8s`. EKS node IAM roles must be able to pull those images.

## Configure and deploy

Complete the managed-data steps above first: apply the namespace and a `payflux-config` ConfigMap populated with the real RDS, ElastiCache, and MSK endpoints, and create the `payflux-secrets` Secret containing the RDS username/password and a strong JWT secret. **Do not apply `secret.example.yaml`**; it is documentation only and contains placeholders. The default Kustomize bundle uses this managed data layer and does not deploy the demo PostgreSQL, Kafka, or Redis workloads. Keep `config.yaml` out of normal Git commits after setting real endpoints; CI deliberately does not apply that file.

Apply the application bundle (workloads, edge services, single public Ingress, monitoring resources, and HPA):

```powershell
kubectl apply -k .\k8s
```

The ALB address appears asynchronously:

```powershell
kubectl get ingress -n payflux -w
kubectl get pods,services -n payflux
kubectl get hpa -n payflux
```

GitHub Actions deploys the manifests and waits for all application Deployments
to finish rolling out. To undo a deployment, use the Deployment's revision
history, for example:

```bash
kubectl rollout undo deployment/frontend -n payflux
```

Replace `frontend` with `api-gateway`, `user-service`, `wallet-service`,
`transaction-service`, `notification-service`, or `reward-service` to roll back
another application. `kubectl rollout undo` restores the previous Pod template;
it does not revert ConfigMaps, Secrets, or other applied resources.

Wait for the ALB address, then set `FRONTEND_URL` in `config.yaml` to the exact browser origin (for example `http://<ALB-DNS>` for this HTTP demo, or your HTTPS custom-domain origin). Re-apply the ConfigMap and restart the gateway so its CORS configuration matches the frontend origin:

```powershell
kubectl apply -f .\k8s\config.yaml
kubectl rollout restart deployment/api-gateway -n payflux
```

The notification and reward Deployments use an init container that waits for Kafka metadata (not just an open TCP port) before starting either consumer. The Kafka readiness probe also waits for a successful broker metadata request.

The Ingress sends `/auth` and `/api` to the gateway and `/` to the frontend. It does not route actuator endpoints publicly. To inspect gateway health, use a local port-forward:

```powershell
kubectl port-forward -n payflux service/api-gateway 8080:8080
curl.exe -i http://localhost:8080/actuator/health
```

The transaction HPA targets 60% CPU utilization of the configured CPU request, within the requested 50–70% range. `metrics-server` must expose `metrics.k8s.io` for the HPA to report metrics and scale.