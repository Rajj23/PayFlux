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
  description = "AWS Region where the single EC2 application server will be created."
  type        = string
  default     = "us-east-1"
}

variable "instance_type" {
  description = "The EC2 instance size. The six Java services plus Kafka require substantially more than a micro instance. This instance incurs EC2 charges."
  type        = string
  default     = "t3.xlarge"

  validation {
    condition     = contains(["t3.large", "t3.xlarge", "t3.2xlarge"], var.instance_type)
    error_message = "Choose an explicitly supported t3.large, t3.xlarge, or t3.2xlarge; a micro instance cannot run the full PayFlux stack."
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
    description = "SSH from the operator's fixed public IPv4 only"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.ssh_cidr]
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
  user_data_replace_on_change = false

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

    # Configure a modest swap file for build-time memory spikes; Docker builds
    # are performed on the server so application deploys need not use ECR.
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
    ufw allow from ${var.ssh_cidr} to any port 22 proto tcp
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

output "server_instance_id" {
  description = "EC2 instance ID for the PayFlux Docker Compose server."
  value       = aws_instance.server.id
}

output "server_public_ip" {
  description = "Public IPv4 address. Point your A record here, then use http://IP until DNS is ready."
  value       = aws_instance.server.public_ip
}

output "server_ssh_command" {
  description = "SSH command to connect to the Ubuntu EC2 instance."
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