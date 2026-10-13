terraform {
  required_version = ">= 1.10.0"

  backend "s3" {
    bucket         = "payflux-terraform-state-313694531077"
    key            = "payflux/infrastructure/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "payflux-terraform-locks"
    encrypt        = true
    use_lockfile   = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = "us-east-1"
}

locals {
  name   = "payflux"
  region = "us-east-1"
}

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.0"

  name = "${local.name}-vpc"
  cidr = "10.0.0.0/16"

  azs             = ["us-east-1a", "us-east-1b"]
  private_subnets = ["10.0.1.0/24", "10.0.2.0/24"]
  public_subnets  = ["10.0.101.0/24", "10.0.102.0/24"]

  enable_nat_gateway = true
  single_nat_gateway = true

  public_subnet_tags = {
    "kubernetes.io/role/elb" = 1
  }

  private_subnet_tags = {
    "kubernetes.io/role/internal-elb" = 1
  }
}

module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.0"

  cluster_name    = "${local.name}-cluster"
  cluster_version = "1.35"

  vpc_id                   = module.vpc.vpc_id
  subnet_ids               = module.vpc.private_subnets
  control_plane_subnet_ids = module.vpc.private_subnets

  cluster_endpoint_public_access = true
  enable_irsa                    = true

  access_entries = {
    github_actions = {
      principal_arn = aws_iam_role.github_actions.arn

      policy_associations = {
        deploy = {
          policy_arn = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSAdminPolicy"
          access_scope = {
            type       = "namespace"
            namespaces = ["payflux"]
          }
        }
      }
    }
  }

  eks_managed_node_groups = {
    payflux-nodes = {
      min_size     = 2
      max_size     = 3
      desired_size = 2

      instance_types = ["t3.medium"]
      capacity_type  = "ON_DEMAND"
    }
  }

  enable_cluster_creator_admin_permissions = true
}

locals {
  services = [
    "payflux-user-service",
    "payflux-wallet-service",
    "payflux-transaction-service",
    "payflux-notification-service",
    "payflux-reward-service",
    "payflux-api-gateway",
    "payflux-frontend"
  ]
}

resource "aws_ecr_repository" "services" {
  for_each = toset(local.services)
  name     = each.key

  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  lifecycle {
    prevent_destroy = false
  }
}

resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1", "1c58a3a8518e8759bf075b76b750d4f2df264fcd"]
}

resource "aws_iam_role" "github_actions" {
  name = "github-actions-payflux"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRoleWithWebIdentity"
        Effect = "Allow"
        Principal = {
          Federated = aws_iam_openid_connect_provider.github.arn
        }
        Condition = {
          StringEquals = {
            "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
            "token.actions.githubusercontent.com:sub" = "repo:Rajj23/PayPal-Clone:ref:refs/heads/main"
          }
        }
      }
    ]
  })
}

resource "aws_iam_role_policy" "github_actions_ecr" {
  name = "payflux-ecr-publish"
  role = aws_iam_role.github_actions.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "AuthenticateToEcr"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid      = "DescribeEksClusterForKubeconfig"
        Effect   = "Allow"
        Action   = ["eks:DescribeCluster"]
        Resource = "arn:aws:eks:${local.region}:313694531077:cluster/${local.name}-cluster"
      },
      {
        Sid    = "PublishPayfluxImages"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:CompleteLayerUpload",
          "ecr:DescribeImages",
          "ecr:DescribeRepositories",
          "ecr:InitiateLayerUpload",
          "ecr:PutImage",
          "ecr:UploadLayerPart"
        ]
        Resource = [for repository in aws_ecr_repository.services : repository.arn]
      }
    ]
  })
}

output "cluster_name" {
  description = "EKS cluster name for aws eks update-kubeconfig"
  value       = module.eks.cluster_name
}

output "cluster_region" {
  description = "AWS region containing the EKS cluster"
  value       = local.region
}

output "ecr_repository_urls" {
  description = "Push and pull URLs for the PayFlux service image repositories"
  value       = { for name, repository in aws_ecr_repository.services : name => repository.repository_url }
}

output "github_actions_role_arn" {
  description = "IAM role ARN to configure in GitHub Actions via OIDC"
  value       = aws_iam_role.github_actions.arn
}

output "vpc_id" {
  description = "VPC ID for EKS add-ons and the AWS Load Balancer Controller"
  value       = module.vpc.vpc_id
}

output "oidc_provider_arn" {
  description = "EKS OIDC provider ARN used by service-account IAM roles"
  value       = module.eks.oidc_provider_arn
}

output "cluster_oidc_issuer_url" {
  description = "EKS OIDC issuer URL used by service-account IAM roles"
  value       = module.eks.cluster_oidc_issuer_url
}
