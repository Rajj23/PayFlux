variable "postgres_password" {
  description = "Strong PostgreSQL master password; supply via TF_VAR_postgres_password or an uncommitted tfvars file"
  type        = string
  sensitive   = true

  validation {
    condition = length(var.postgres_password) >= 16 && length(var.postgres_password) <= 128 && alltrue([
      for character in ["/", "@", "\"", " "] : !strcontains(var.postgres_password, character)
    ])
    error_message = "postgres_password must be 16-128 characters and must not contain slash, at-sign, quote, or space."
  }
}

resource "aws_security_group" "data_layer" {
  name_prefix = "${local.name}-data-"
  description = "Allow the EKS worker nodes to access PayFlux managed data services"
  vpc_id      = module.vpc.vpc_id

  ingress {
    description     = "PostgreSQL from EKS worker nodes"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [module.eks.node_security_group_id]
  }

  ingress {
    description     = "Redis from EKS worker nodes"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [module.eks.node_security_group_id]
  }

  ingress {
    description     = "Kafka from EKS worker nodes"
    from_port       = 9092
    to_port         = 9092
    protocol        = "tcp"
    security_groups = [module.eks.node_security_group_id]
  }

  ingress {
    description = "MSK broker-to-broker traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    self        = true
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${local.name}-data-layer"
  }
}

resource "aws_db_subnet_group" "postgres" {
  name       = "${local.name}-postgres"
  subnet_ids = module.vpc.private_subnets

  tags = {
    Name = "${local.name}-postgres"
  }
}

resource "aws_db_instance" "postgres" {
  identifier                 = "${local.name}-postgres"
  engine                     = "postgres"
  engine_version             = "16.15"
  instance_class             = "db.t3.micro"
  allocated_storage          = 20
  max_allocated_storage      = 100
  storage_type               = "gp3"
  storage_encrypted          = true
  db_name                    = "payflux"
  username                   = "payflux"
  password                   = var.postgres_password
  port                       = 5432
  db_subnet_group_name       = aws_db_subnet_group.postgres.name
  vpc_security_group_ids     = [aws_security_group.data_layer.id]
  publicly_accessible        = false
  multi_az                   = false
  backup_retention_period    = 7
  auto_minor_version_upgrade = true
  deletion_protection        = false
  skip_final_snapshot        = true
  apply_immediately          = true

  tags = {
    Name = "${local.name}-postgres"
  }
}

resource "aws_elasticache_subnet_group" "redis" {
  name       = "${local.name}-redis"
  subnet_ids = module.vpc.private_subnets
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id       = "${local.name}-redis"
  description                = "PayFlux private Redis cache"
  engine                     = "redis"
  engine_version             = "7.1"
  node_type                  = "cache.t3.micro"
  port                       = 6379
  num_cache_clusters         = 1
  automatic_failover_enabled = false
  multi_az_enabled           = false
  at_rest_encryption_enabled = true
  transit_encryption_enabled = false
  subnet_group_name          = aws_elasticache_subnet_group.redis.name
  security_group_ids         = [aws_security_group.data_layer.id]
  apply_immediately          = true

  tags = {
    Name = "${local.name}-redis"
  }
}

resource "aws_msk_cluster" "kafka" {
  cluster_name           = "${local.name}-kafka"
  kafka_version          = "3.8.1"
  number_of_broker_nodes = 2

  broker_node_group_info {
    instance_type   = "kafka.t3.small"
    client_subnets  = module.vpc.private_subnets
    security_groups = [aws_security_group.data_layer.id]

    storage_info {
      ebs_storage_info {
        volume_size = 20
      }
    }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "PLAINTEXT"
      in_cluster    = true
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.kafka.name
      }
    }
  }

  tags = {
    Name = "${local.name}-kafka"
  }
}

resource "aws_cloudwatch_log_group" "kafka" {
  name              = "/aws/msk/${local.name}-kafka"
  retention_in_days = 14
}

output "postgres_host" {
  description = "Private RDS PostgreSQL endpoint for the payflux namespace"
  value       = aws_db_instance.postgres.address
}

output "postgres_username" {
  description = "RDS PostgreSQL application username"
  value       = aws_db_instance.postgres.username
}

output "postgres_password" {
  description = "RDS PostgreSQL password; transfer directly into the Kubernetes Secret"
  value       = var.postgres_password
  sensitive   = true
}

output "redis_host" {
  description = "Private ElastiCache Redis endpoint"
  value       = aws_elasticache_replication_group.redis.primary_endpoint_address
}

output "kafka_bootstrap_brokers" {
  description = "Private MSK PLAINTEXT bootstrap brokers for EKS clients"
  value       = aws_msk_cluster.kafka.bootstrap_brokers
}