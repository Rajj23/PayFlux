# Kubernetes deployment

The Kubernetes bundle runs only the PayFlux application workloads. Kafka,
PostgreSQL, and Redis run on the EC2 data host provisioned by
[`terraform/server`](../terraform/server).

Before applying, update [`config.yaml`](./config.yaml) with the EC2 private IP,
create `payflux-secrets` from [`secret.example.yaml`](./secret.example.yaml),
and ensure the GHCR images referenced by [`kustomization.yaml`](./kustomization.yaml)
are public and available.

```bash
aws eks update-kubeconfig --name payflux-cluster --region us-east-1
kubectl apply -k ./k8s
kubectl get pods -n payflux
```

The bundle intentionally has no in-cluster data services, monitoring stack,
cloud load balancer, or storage add-on. Use `kubectl port-forward` for a demo
endpoint or add an ingress/controller separately when that cost is acceptable.
