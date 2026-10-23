terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

variable "aws_region" {
  description = "AWS Region for the EC2 data host and EKS cluster."
  type        = string
  default     = "us-east-1"
}

variable "instance_type" {
  description = "The EC2 data host size."
  type        = string
  default     = "m7i-flex.large"

  validation {
    condition     = contains(["m7i-flex.large"], var.instance_type)
    error_message = "The supported PayFlux data host is m7i-flex.large."
  }
}

variable "ssh_cidr" {
  description = "Your public IP address in CIDR notation, for example 203.0.113.10/32. Never open SSH to the entire internet."
  type        = string

  validation {
    condition     = can(cidrhost(var.ssh_cidr, 0)) && can(regex("/32$", var.ssh_cidr))
    error_message = "ssh_cidr must be a valid single IPv4 address in /32 notation."
  }
}

variable "ssh_public_key" {
  description = "Contents of the SSH public key to use when connecting to the new Ubuntu server."
  type        = string

  validation {
    condition     = can(regex("^ssh-(rsa|ed25519) ", var.ssh_public_key))
    error_message = "Provide the OpenSSH-format contents of an ssh-rsa or ssh-ed25519 public key."
  }
}

variable "ssh_public_key_path" {
  description = "Absolute local path to the authorized OpenSSH public-key file whose contents match ssh_public_key."
  type        = string

  validation {
    condition     = length(trimspace(var.ssh_public_key_path)) > 0
    error_message = "Provide the absolute path to the authorized public key matching ssh_public_key."
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_ami" "ubuntu_2404" {
  most_recent = true
  owners      = ["099720109477"]

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }

  filter {
    name   = "architecture"
    values = ["x86_64"]
  }
}

resource "aws_vpc" "server" {
  cidr_block           = "10.31.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "payflux-server-vpc" }
}

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.server.id
  cidr_block              = "10.31.1.0/24"
  availability_zone       = data.aws_availability_zones.available.names[0]
  map_public_ip_on_launch = true

  tags = { Name = "payflux-server-public" }
}

resource "aws_subnet" "public_secondary" {
  vpc_id                  = aws_vpc.server.id
  cidr_block              = "10.31.2.0/24"
  availability_zone       = data.aws_availability_zones.available.names[1]
  map_public_ip_on_launch = true

  tags = { Name = "payflux-server-public-secondary" }
}

resource "aws_route_table_association" "public_secondary" {
  subnet_id      = aws_subnet.public_secondary.id
  route_table_id = aws_route_table.public.id
}

resource "aws_internet_gateway" "server" {
  vpc_id = aws_vpc.server.id

  tags = { Name = "payflux-server-igw" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.server.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.server.id
  }

  tags = { Name = "payflux-server-public" }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_security_group" "server" {
  name_prefix = "payflux-server-"
  description = "Public web traffic and operator-only SSH to the single PayFlux demo server."
  vpc_id      = aws_vpc.server.id

  ingress {
    description = "HTTP and certificate renewal"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS application traffic"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "SSH from the operators fixed public IPv4 only"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.ssh_cidr]
  }

  ingress {
    description = "PostgreSQL from EKS nodes"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = ["10.31.0.0/16"]
  }

  ingress {
    description = "Redis from EKS nodes"
    from_port   = 6379
    to_port     = 6379
    protocol    = "tcp"
    cidr_blocks = ["10.31.0.0/16"]
  }

  ingress {
    description = "Kafka from EKS nodes"
    from_port   = 9092
    to_port     = 9092
    protocol    = "tcp"
    cidr_blocks = ["10.31.0.0/16"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "payflux-server" }
}

resource "aws_key_pair" "server" {
  key_name   = "payflux-server-${substr(sha256(var.ssh_public_key), 0, 12)}"
  public_key = file(pathexpand(var.ssh_public_key_path))

  tags = { Name = "payflux-server" }
}

resource "aws_instance" "server" {
  ami                         = data.aws_ami.ubuntu_2404.id
  instance_type               = var.instance_type
  subnet_id                   = aws_subnet.public.id
  vpc_security_group_ids      = [aws_security_group.server.id]
  key_name                    = aws_key_pair.server.key_name
  associate_public_ip_address = true
  monitoring                  = false
  user_data_replace_on_change = true

  root_block_device {
    volume_type           = "gp3"
    volume_size           = 40
    encrypted             = true
    delete_on_termination = true
  }

  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required"
  }

  user_data = <<-CLOUDINIT
    #!/bin/bash
    set -euxo pipefail

    exec > >(tee -a /var/log/payflux-bootstrap.log) 2>&1

    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates curl git ufw
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc

    . /etc/os-release
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $${UBUNTU_CODENAME:-$VERSION_CODENAME} stable" > /etc/apt/sources.list.d/docker.list
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

    systemctl enable --now docker

    # Keep Kafka and the data containers stable on the small data host.
    fallocate -l 4G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=4096
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab

    install -d -o ubuntu -g ubuntu -m 0755 /opt/payflux

    # Host firewall complements the EC2 security group; container-published
    # ports can bypass UFW on Ubuntu, so EC2 security-group rules are decisive.
    ufw default deny incoming
    ufw default allow outgoing
    # The EC2 security group restricts SSH to var.ssh_cidr. Keeping the host
    # firewall open for port 22 avoids stale rules after a public IP changes.
    ufw allow 22/tcp
    ufw allow 80/tcp
    ufw allow 443/tcp
    ufw --force enable

    echo "PayFlux server bootstrap complete; clone the repository into /opt/payflux and run deploy.sh." > /etc/motd
  CLOUDINIT

  tags = {
    Name    = "payflux-server"
    Project = "payflux"
  }

}

module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.0"

  cluster_name    = "payflux-cluster"
  cluster_version = "1.35"

  vpc_id                   = aws_vpc.server.id
  subnet_ids               = [aws_subnet.public.id, aws_subnet.public_secondary.id]
  control_plane_subnet_ids = [aws_subnet.public.id, aws_subnet.public_secondary.id]

  cluster_endpoint_public_access           = true
  enable_irsa                              = true
  enable_cluster_creator_admin_permissions = true

  eks_managed_node_groups = {
    payflux = {
      min_size       = 1
      max_size       = 2
      desired_size   = 1
      instance_types = ["m7i-flex.large"]
      capacity_type  = "ON_DEMAND"
    }
  }

  depends_on = [aws_instance.server]
}

output "server_instance_id" {
  description = "EC2 instance ID for the PayFlux data host."
  value       = aws_instance.server.id
}

output "server_public_ip" {
  description = "Public IPv4 address for SSH administration."
  value       = aws_instance.server.public_ip
}

output "server_ssh_command" {
  description = "SSH command to connect to the EC2 data host."
  value       = "ssh -i <PRIVATE_KEY.pem> ubuntu@${aws_instance.server.public_ip}"
}

output "server_instance_type" {
  value = aws_instance.server.instance_type
}

output "server_region" {
  value = var.aws_region
}

output "server_storage_gb" {
  value = 40
}

output "server_private_ip" {
  description = "Private IP used by EKS to reach the EC2 data services."
  value       = aws_instance.server.private_ip
}

output "cluster_name" {
  value = module.eks.cluster_name
}

output "cluster_region" {
  value = var.aws_region
}