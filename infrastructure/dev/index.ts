/**
 * OSC Development Stack
 *
 * Provisions a self-contained development environment in AWS us-east-1:
 *   - VPC with public + private subnets across 2 AZs
 *   - RDS PostgreSQL 16 (db.t3.micro, single-AZ) in private subnets
 *   - ECS Fargate cluster with 1 task (256 CPU / 512 MB)
 *   - ALB in public subnets routing HTTP to the Fargate service
 *   - Security groups with least-privilege rules
 *
 * Outputs: albDnsName, dbEndpoint
 */

import * as pulumi from "@pulumi/pulumi";
import * as aws from "@pulumi/aws";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const config = new pulumi.Config();
const env = "dev";
const region = "us-east-1";
const dbPassword = config.requireSecret("dbPassword");
const imageTag = config.get("imageTag") ?? "latest";
const ecrRepository = config.require("ecrRepository");

const tags: Record<string, string> = {
    Environment: env,
    Project: "osc",
    ManagedBy: "pulumi",
};

// ---------------------------------------------------------------------------
// VPC — 2 AZs, public + private subnets
// ---------------------------------------------------------------------------

const vpc = new aws.ec2.Vpc("osc-dev-vpc", {
    cidrBlock: "10.10.0.0/16",
    enableDnsHostnames: true,
    enableDnsSupport: true,
    tags: { ...tags, Name: "osc-dev-vpc" },
});

const internetGateway = new aws.ec2.InternetGateway("osc-dev-igw", {
    vpcId: vpc.id,
    tags: { ...tags, Name: "osc-dev-igw" },
});

// Public subnets (ALB)
const publicSubnet1 = new aws.ec2.Subnet("osc-dev-public-1", {
    vpcId: vpc.id,
    cidrBlock: "10.10.0.0/24",
    availabilityZone: `${region}a`,
    mapPublicIpOnLaunch: true,
    tags: { ...tags, Name: "osc-dev-public-1", Tier: "public" },
});

const publicSubnet2 = new aws.ec2.Subnet("osc-dev-public-2", {
    vpcId: vpc.id,
    cidrBlock: "10.10.1.0/24",
    availabilityZone: `${region}b`,
    mapPublicIpOnLaunch: true,
    tags: { ...tags, Name: "osc-dev-public-2", Tier: "public" },
});

// Private subnets (Fargate + RDS)
const privateSubnet1 = new aws.ec2.Subnet("osc-dev-private-1", {
    vpcId: vpc.id,
    cidrBlock: "10.10.10.0/24",
    availabilityZone: `${region}a`,
    tags: { ...tags, Name: "osc-dev-private-1", Tier: "private" },
});

const privateSubnet2 = new aws.ec2.Subnet("osc-dev-private-2", {
    vpcId: vpc.id,
    cidrBlock: "10.10.11.0/24",
    availabilityZone: `${region}b`,
    tags: { ...tags, Name: "osc-dev-private-2", Tier: "private" },
});

// Public route table → Internet Gateway
const publicRouteTable = new aws.ec2.RouteTable("osc-dev-public-rt", {
    vpcId: vpc.id,
    routes: [{
        cidrBlock: "0.0.0.0/0",
        gatewayId: internetGateway.id,
    }],
    tags: { ...tags, Name: "osc-dev-public-rt" },
});

new aws.ec2.RouteTableAssociation("osc-dev-public-rta-1", {
    subnetId: publicSubnet1.id,
    routeTableId: publicRouteTable.id,
});

new aws.ec2.RouteTableAssociation("osc-dev-public-rta-2", {
    subnetId: publicSubnet2.id,
    routeTableId: publicRouteTable.id,
});

// NAT Gateway for private subnets
const natEip = new aws.ec2.Eip("osc-dev-nat-eip", {
    domain: "vpc",
    tags: { ...tags, Name: "osc-dev-nat-eip" },
});

const natGateway = new aws.ec2.NatGateway("osc-dev-nat", {
    subnetId: publicSubnet1.id,
    allocationId: natEip.id,
    tags: { ...tags, Name: "osc-dev-nat" },
});

// Private route table → NAT Gateway
const privateRouteTable = new aws.ec2.RouteTable("osc-dev-private-rt", {
    vpcId: vpc.id,
    routes: [{
        cidrBlock: "0.0.0.0/0",
        natGatewayId: natGateway.id,
    }],
    tags: { ...tags, Name: "osc-dev-private-rt" },
});

new aws.ec2.RouteTableAssociation("osc-dev-private-rta-1", {
    subnetId: privateSubnet1.id,
    routeTableId: privateRouteTable.id,
});

new aws.ec2.RouteTableAssociation("osc-dev-private-rta-2", {
    subnetId: privateSubnet2.id,
    routeTableId: privateRouteTable.id,
});

// ---------------------------------------------------------------------------
// Security Groups
// ---------------------------------------------------------------------------

// ALB — accepts HTTP (80) from the internet
const albSg = new aws.ec2.SecurityGroup("osc-dev-alb-sg", {
    vpcId: vpc.id,
    description: "OSC dev ALB — internet-facing",
    ingress: [
        {
            protocol: "tcp",
            fromPort: 80,
            toPort: 80,
            cidrBlocks: ["0.0.0.0/0"],
            description: "HTTP from internet",
        },
    ],
    egress: [
        { protocol: "-1", fromPort: 0, toPort: 0, cidrBlocks: ["0.0.0.0/0"], description: "All outbound" },
    ],
    tags: { ...tags, Name: "osc-dev-alb-sg" },
});

// Fargate — accepts 8080 only from ALB security group
const appSg = new aws.ec2.SecurityGroup("osc-dev-app-sg", {
    vpcId: vpc.id,
    description: "OSC dev Fargate tasks",
    ingress: [
        {
            protocol: "tcp",
            fromPort: 8080,
            toPort: 8080,
            securityGroups: [albSg.id],
            description: "API from ALB",
        },
    ],
    egress: [
        { protocol: "-1", fromPort: 0, toPort: 0, cidrBlocks: ["0.0.0.0/0"], description: "All outbound" },
    ],
    tags: { ...tags, Name: "osc-dev-app-sg" },
});

// RDS — accepts 5432 only from Fargate security group
const dbSg = new aws.ec2.SecurityGroup("osc-dev-db-sg", {
    vpcId: vpc.id,
    description: "OSC dev RDS PostgreSQL",
    ingress: [
        {
            protocol: "tcp",
            fromPort: 5432,
            toPort: 5432,
            securityGroups: [appSg.id],
            description: "PostgreSQL from Fargate",
        },
    ],
    egress: [
        { protocol: "-1", fromPort: 0, toPort: 0, cidrBlocks: ["0.0.0.0/0"], description: "All outbound" },
    ],
    tags: { ...tags, Name: "osc-dev-db-sg" },
});

// ---------------------------------------------------------------------------
// RDS PostgreSQL 16 (dev — single-AZ, db.t3.micro)
// ---------------------------------------------------------------------------

const dbSubnetGroup = new aws.rds.SubnetGroup("osc-dev-db-subnet-group", {
    subnetIds: [privateSubnet1.id, privateSubnet2.id],
    tags: { ...tags, Name: "osc-dev-db-subnet-group" },
});

const db = new aws.rds.Instance("osc-dev-postgres", {
    engine: "postgres",
    engineVersion: "16.3",
    instanceClass: "db.t3.micro",
    allocatedStorage: 20,
    storageType: "gp3",
    dbName: "osc",
    username: "osc_admin",
    password: dbPassword,
    dbSubnetGroupName: dbSubnetGroup.name,
    vpcSecurityGroupIds: [dbSg.id],
    multiAz: false,
    backupRetentionPeriod: 1,
    deletionProtection: false,
    skipFinalSnapshot: true,
    tags: { ...tags, Name: "osc-dev-postgres" },
});

// Store credentials in Secrets Manager
const dbSecret = new aws.secretsmanager.Secret("osc-dev-db-secret", {
    description: "OSC dev PostgreSQL credentials",
    tags,
});

new aws.secretsmanager.SecretVersion("osc-dev-db-secret-version", {
    secretId: dbSecret.id,
    secretString: pulumi.interpolate`{"host":"${db.address}","port":"${db.port}","dbname":"osc","username":"osc_admin","password":"${dbPassword}"}`,
});

// ---------------------------------------------------------------------------
// ECS Fargate (1 task, 256 CPU / 512 MB)
// ---------------------------------------------------------------------------

const cluster = new aws.ecs.Cluster("osc-dev-cluster", {
    tags: { ...tags, Name: "osc-dev-cluster" },
});

// CloudWatch log group
const logGroup = new aws.cloudwatch.LogGroup("osc-dev-logs", {
    name: "/ecs/osc-dev",
    retentionInDays: 7,
    tags,
});

// Task execution role
const executionRole = new aws.iam.Role("osc-dev-exec-role", {
    assumeRolePolicy: JSON.stringify({
        Version: "2012-10-17",
        Statement: [{
            Effect: "Allow",
            Principal: { Service: "ecs-tasks.amazonaws.com" },
            Action: "sts:AssumeRole",
        }],
    }),
    managedPolicyArns: [
        "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy",
    ],
    tags,
});

// Allow reading the DB secret
new aws.iam.RolePolicy("osc-dev-exec-secret-policy", {
    role: executionRole.id,
    policy: dbSecret.arn.apply(arn => JSON.stringify({
        Version: "2012-10-17",
        Statement: [{
            Effect: "Allow",
            Action: ["secretsmanager:GetSecretValue"],
            Resource: arn,
        }],
    })),
});

const taskDefinition = new aws.ecs.TaskDefinition("osc-dev-task", {
    family: "osc-api-dev",
    networkMode: "awsvpc",
    requiresCompatibilities: ["FARGATE"],
    cpu: "256",
    memory: "512",
    executionRoleArn: executionRole.arn,
    containerDefinitions: pulumi.all([ecrRepository, db.address, db.port, logGroup.name]).apply(
        ([repo, host, port, lgName]) => JSON.stringify([{
            name: "osc-api",
            image: `${repo}/osc-api:${imageTag}`,
            portMappings: [{ containerPort: 8080, protocol: "tcp" }],
            environment: [
                { name: "SPRING_R2DBC_URL", value: `r2dbc:postgresql://${host}:${port}/osc` },
                { name: "SPRING_PROFILES_ACTIVE", value: "default" },
                { name: "OSC_AWS_SECRETS_MANAGER_ENABLED", value: "true" },
            ],
            logConfiguration: {
                logDriver: "awslogs",
                options: {
                    "awslogs-group": lgName,
                    "awslogs-region": region,
                    "awslogs-stream-prefix": "ecs",
                },
            },
            essential: true,
        }]),
    ),
    tags,
});

// ---------------------------------------------------------------------------
// ALB → Target Group → Listener
// ---------------------------------------------------------------------------

const alb = new aws.lb.LoadBalancer("osc-dev-alb", {
    internal: false,
    loadBalancerType: "application",
    securityGroups: [albSg.id],
    subnets: [publicSubnet1.id, publicSubnet2.id],
    tags: { ...tags, Name: "osc-dev-alb" },
});

const targetGroup = new aws.lb.TargetGroup("osc-dev-tg", {
    port: 8080,
    protocol: "HTTP",
    targetType: "ip",
    vpcId: vpc.id,
    healthCheck: {
        path: "/actuator/health",
        interval: 30,
        healthyThreshold: 2,
        unhealthyThreshold: 3,
        timeout: 5,
    },
    tags: { ...tags, Name: "osc-dev-tg" },
});

new aws.lb.Listener("osc-dev-listener", {
    loadBalancerArn: alb.arn,
    port: 80,
    protocol: "HTTP",
    defaultActions: [{ type: "forward", targetGroupArn: targetGroup.arn }],
});

// ECS Service
new aws.ecs.Service("osc-dev-service", {
    cluster: cluster.arn,
    taskDefinition: taskDefinition.arn,
    desiredCount: 1,
    launchType: "FARGATE",
    networkConfiguration: {
        subnets: [privateSubnet1.id, privateSubnet2.id],
        securityGroups: [appSg.id],
        assignPublicIp: false,
    },
    loadBalancers: [{
        targetGroupArn: targetGroup.arn,
        containerName: "osc-api",
        containerPort: 8080,
    }],
    tags,
});

// ---------------------------------------------------------------------------
// Outputs
// ---------------------------------------------------------------------------

export const albDnsName = alb.dnsName;
export const dbEndpoint = db.endpoint;
