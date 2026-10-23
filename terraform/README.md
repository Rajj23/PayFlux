# Terraform deployment

PayFlux uses one AWS Terraform root: [`server`](./server).

It provisions an `m7i-flex.large` EC2 data host, an EKS cluster, and one EKS
node group. Kafka, PostgreSQL, and Redis run as containers on the EC2 host;
application workloads run in EKS. The deployment does not create MSK, RDS,
ElastiCache, NAT gateways, load balancers, or ECR repositories.

```powershell
.\deploy\aws-server.ps1
```

The `m7i-flex.large` type is marked Free Tier-eligible by the target account,
but EKS control-plane hours, EKS worker hours, storage, public IPv4 usage, and
current AWS terms still determine billing. Review the Terraform plan and AWS
pricing before confirming an apply.

The former `infrastructure`, `addons`, and `backend` roots were removed because
they provisioned paid managed services and could be applied accidentally. The
server root intentionally uses local Terraform state; keep
`deploy/server-config.tfvars` private and back up application data separately.
