resource "aws_vpc" "migration" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags = { Name = "${var.project_name}-${var.environment}-vpc" }
}

resource "aws_subnet" "private" {
  for_each = { for index, cidr in var.private_subnet_cidrs : tostring(index) => cidr }
  vpc_id            = aws_vpc.migration.id
  cidr_block        = each.value
  availability_zone = data.aws_availability_zones.available.names[tonumber(each.key)]
  map_public_ip_on_launch = false
  tags = {
    Name = "${var.project_name}-${var.environment}-private-${tonumber(each.key) + 1}"
    Tier = "private"
  }
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.migration.id
  tags   = { Name = "${var.project_name}-${var.environment}-private" }
}

resource "aws_route_table_association" "private" {
  for_each       = aws_subnet.private
  subnet_id      = each.value.id
  route_table_id = aws_route_table.private.id
}

resource "aws_security_group" "worker" {
  name        = "${var.project_name}-${var.environment}-worker"
  description = "Outbound-only security group for migration Fargate tasks."
  vpc_id      = aws_vpc.migration.id
  egress {
    description = "Allow worker access to AWS endpoints and approved source services."
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "${var.project_name}-${var.environment}-worker" }
}

resource "aws_security_group" "endpoints" {
  name        = "${var.project_name}-${var.environment}-endpoints"
  description = "HTTPS access to interface endpoints from worker tasks."
  vpc_id      = aws_vpc.migration.id
  ingress {
    description     = "HTTPS from worker tasks."
    from_port       = 443
    to_port         = 443
    protocol        = "tcp"
    security_groups = [aws_security_group.worker.id]
  }
  egress {
    description = "HTTPS response traffic within the VPC."
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }
  tags = { Name = "${var.project_name}-${var.environment}-endpoints" }
}

locals {
  interface_endpoint_services = toset(["ecr.api", "ecr.dkr", "logs"])
}

resource "aws_vpc_endpoint" "interface" {
  for_each            = local.interface_endpoint_services
  vpc_id               = aws_vpc.migration.id
  vpc_endpoint_type    = "Interface"
  service_name         = "com.amazonaws.${var.aws_region}.${each.value}"
  subnet_ids           = [for subnet in aws_subnet.private : subnet.id]
  security_group_ids   = [aws_security_group.endpoints.id]
  private_dns_enabled  = true
  tags = { Name = "${var.project_name}-${var.environment}-${each.value}" }
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.migration.id
  vpc_endpoint_type = "Gateway"
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  route_table_ids   = [aws_route_table.private.id]
  tags = { Name = "${var.project_name}-${var.environment}-s3" }
}

resource "aws_vpc_endpoint" "dynamodb" {
  vpc_id            = aws_vpc.migration.id
  vpc_endpoint_type = "Gateway"
  service_name      = "com.amazonaws.${var.aws_region}.dynamodb"
  route_table_ids   = [aws_route_table.private.id]
  tags = { Name = "${var.project_name}-${var.environment}-dynamodb" }
}

resource "aws_ecr_repository" "worker" {
  name                 = var.ecr_repository_name
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
  encryption_configuration { encryption_type = "AES256" }
  tags = { Name = var.ecr_repository_name }
}

resource "aws_ecr_lifecycle_policy" "worker" {
  repository = aws_ecr_repository.worker.name
  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Retain the most recent tagged worker images."
        selection = {
          tagStatus     = "tagged"
          tagPrefixList = ["sha-"]
          countType     = "imageCountMoreThan"
          countNumber   = var.ecr_image_retention_count
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Expire untagged images after one day."
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      }
    ]
  })
}
