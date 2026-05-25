/**
 * OSC Production Stack
 *
 * Provisions a high-availability production environment in AWS us-east-1:
 *   - VPC with public + private subnets across 3 AZs
 *   - RDS PostgreSQL 16 (db.t3.medium, Multi-AZ, 7-day backups) in private subnets
 *   - ECS Fargate with auto-scaling (min 2, max 10 tasks, 512 CPU / 1024 MB)
 *   - ALB with HTTPS listener + ACM certificate, HTTP→HTTPS redirect
 *   - WAF v2 with rate-limiting rule
 *   - Security groups with least-privilege rules
 *
 * Outputs: albDnsName, dbEndpoint, certificateArn
 */

import * as pulumi from "@pulumi/pulumi";
import * as aws from "@pulumi/aws";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const config = new pulumi.Config();
const env = "prod";
const region = "us-east-1";
const dbPassword = config.requireSecret("dbPassword");
const imageTag = config.get("imageTag") ?? "latest";
const ecrRepository = config.require("ecrRepository");
const domainName = config.require("domainName");          // e.g. api.osc.example.com
const hostedZoneId = config.require("hostedZoneId");      // Route53 hosted zone

const tags: Record<string, string> = {
    Environment: env,
    Project: "osc",
    ManagedBy: "pulumi",
};

// ---------------------------------------------------------------------------
// VPC — 3 AZs, public + private subnets
// ---------------------------------------------------------------------------

const vpc = new aws.ec2.Vpc("osc-prod-vpc", {
    cidrBlock: "10.20.0.0/16",
    enableDnsHostnames: true,
    enableDnsSupport: true,
    tags: { ...tags, Name: "osc-prod-vpc" },
});

const internetGateway = new aws.ec2.InternetGateway("osc-prod-igw", {
    vpcId: vpc.id,
    tags: { ...tags, Name: "osc-prod-igw" },
});

// Public subnets (ALB)
const publicSubnet1 = new aws.ec2.Subnet("osc-prod-public-1", {
    vpcId: vpc.id,
    cidrBlock: "10.20.0.0/24",
    availabilityZone: `${region}a`,
    mapPublicIpOnLaunch: true,
    tags: { ...tags, Name: "osc-prod-public-1", Tier: "public" },
});

const publicSubnet2 = new aws.ec2.Subnet("osc-prod-public-2", {
    vpcId: vpc.id,
    cidrBlock: "10.20.1.0/24",
    availabilityZone: `${region}b`,
    mapPublicIpOnLaunch: true,
    tags: { ...tags, Name: "osc-prod-public-2", Tier: "public" },
});

const publicSubnet3 = new aws.ec2.Subnet("osc-prod-public-3", {
    vpcId: vpc.id,
    cidrBlock: "10.20.2.0/24",
    availabilityZone: `${region}c`,
    mapPublicIpOnLaunch: true,
    tags: { ...tags, Name: "osc-prod-public-3", Tier: "public" },
});

// Private subnets (Fargate + RDS)
const privateSubnet1 = new aws.ec2.Subnet("osc-prod-private-1", {
    vpcId: vpc.id,
    cidrBlock: "10.20.10.0/24",
    availabilityZone: `${region}a`,
    tags: { ...tags, Name: "osc-prod-private-1", Tier: "private" },
});

const privateSubnet2 = new aws.ec2.Subnet("osc-prod-private-2", {
    vpcId: vpc.id,
    cidrBlock: "10.20.11.0/24",
    availabilityZone: `${region}b`,
    tags: { ...tags, Name: "osc-prod-private-2", Tier: "private" },
});

const privateSubnet3 = new aws.ec2.Subnet("osc-prod-private-3", {
    vpcId: vpc.id,
    cidrBlock: "10.20.12.0/24",
    availabilityZone: `${region}c`,
    tags: { ...tags, Name: "osc-prod-private-3", Tier: "private" },
});

// Public route table → Internet Gateway
const publicRouteTable = new aws.ec2.RouteTable("osc-prod-public-rt", {
    vpcId: vpc.id,
    routes: [{
        cidrBlock: "0.0.0.0/0",
        gatewayId: internetGateway.id,
    }],
    tags: { ...tags, Name: "osc-prod-public-rt" },
});

new aws.ec2.RouteTableAssociation("osc-prod-public-rta-1", {
    subnetId: publicSubnet1.id,
    routeTableId: publicRouteTable.id,
});
new aws.ec2.RouteTableAssociation("osc-prod-public-rta-2", {
    subnetId: publicSubnet2.id,
    routeTableId: publicRouteTable.id,
});
new aws.ec2.RouteTableAssociation("osc-prod-public-rta-3", {
    subnetId: publicSubnet3.id,
    routeTableId: publicRouteTable.id,
});

// NAT Gateway in AZ-a (single NAT for cost; can add per-AZ for HA)
const natEip = new aws.ec2.Eip("osc-prod-nat-eip", {
    domain: "vpc",
    tags: { ...tags, Name: "osc-prod-nat-eip" },
});

const natGateway = new aws.ec2.NatGateway("osc-prod-nat", {
    subnetId: publicSubnet1.id,
    allocationId: natEip.id,
    tags: { ...tags, Name: "osc-prod-nat" },
});

const privateRouteTable = new aws.ec2.RouteTable("osc-prod-private-rt", {
    vpcId: vpc.id,
    routes: [{
        cidrBlock: "0.0.0.0/0",
        natGatewayId: natGateway.id,
    }],
    tags: { ...tags, Name: "osc-prod-private-rt" },
});

new aws.ec2.RouteTableAssociation("osc-prod-private-rta-1", {
    subnetId: privateSubnet1.id,
    routeTableId: privateRouteTable.id,
});
new aws.ec2.RouteTableAssociation("osc-prod-private-rta-2", {
    subnetId: privateSubnet2.id,
    routeTableId: privateRouteTable.id,
});
new aws.ec2.RouteTableAssociation("osc-prod-private-rta-3", {
    subnetId: privateSubnet3.id,
    routeTableId: privateRouteTable.id,
});

// ---------------------------------------------------------------------------
// ACM Certificate (DNS validation)
// ---------------------------------------------------------------------------

const certificate = new aws.acm.Certificate("osc-prod-cert", {
    domainName,
    validationMethod: "DNS",
    tags,
});

// Create DNS validation records in Route53
const certValidationRecord = new aws.route53.Record("osc-prod-cert-validation", {
    zoneId: hostedZoneId,
    name: certificate.domainValidationOptions[0].resourceRecordName,
    type: certificate.domainValidationOptions[0].resourceRecordType,
    records: [certificate.domainValidationOptions[0].resourceRecordValue],
    ttl: 60,
});

const certValidation = new aws.acm.CertificateValidation("osc-prod-cert-validation", {
    certificateArn: certificate.arn,
    validationRecordFqdns: [certValidationRecord.fqdn],
});

// ---------------------------------------------------------------------------
// Security Groups
// ---------------------------------------------------------------------------

// ALB — accepts HTTP(80) + HTTPS(443) from the internet
const albSg = new aws.ec2.SecurityGroup("osc-prod-alb-sg", {
    vpcId: vpc.id,
    description: "OSC prod ALB — internet-facing",
    ingress: [
        {
            protocol: "tcp",
            fromPort: 80,
            toPort: 80,
            cidrBlocks: ["0.0.0.0/0"],
            description: "HTTP from internet",
        },
        {
            protocol: "tcp",
            fromPort: 443,
            toPort: 443,
            cidrBlocks: ["0.0.0.0/0"],
            description: "HTTPS from internet",
        },
    ],
    egress: [
        { protocol: "-1", fromPort: 0, toPort: 0, cidrBlocks: ["0.0.0.0/0"], description: "All outbound" },
    ],
    tags: { ...tags, Name: "osc-prod-alb-sg" },
});

// Fargate — accepts 8080 only from ALB
const appSg = new aws.ec2.SecurityGroup("osc-prod-app-sg", {
    vpcId: vpc.id,
    description: "OSC prod Fargate tasks",
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
    tags: { ...tags, Name: "osc-prod-app-sg" },
});

// RDS — accepts 5432 only from Fargate
const dbSg = new aws.ec2.SecurityGroup("osc-prod-db-sg", {
    vpcId: vpc.id,
    description: "OSC prod RDS PostgreSQL",
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
    tags: { ...tags, Name: "osc-prod-db-sg" },
});

// ---------------------------------------------------------------------------
// RDS PostgreSQL 16 (prod — Multi-AZ, db.t3.medium, 7-day backups)
// ---------------------------------------------------------------------------

const dbSubnetGroup = new aws.rds.SubnetGroup("osc-prod-db-subnet-group", {
    subnetIds: [privateSubnet1.id, privateSubnet2.id, privateSubnet3.id],
    tags: { ...tags, Name: "osc-prod-db-subnet-group" },
});

const db = new aws.rds.Instance("osc-prod-postgres", {
    engine: "postgres",
    engineVersion: "16.3",
    instanceClass: "db.t3.medium",
    allocatedStorage: 100,
    maxAllocatedStorage: 1000,
    storageType: "gp3",
    dbName: "osc",
    username: "osc_admin",
    password: dbPassword,
    dbSubnetGroupName: dbSubnetGroup.name,
    vpcSecurityGroupIds: [dbSg.id],
    multiAz: true,
    backupRetentionPeriod: 7,
    backupWindow: "03:00-04:00",
    maintenanceWindow: "sun:04:00-sun:05:00",
    deletionProtection: true,
    skipFinalSnapshot: false,
    finalSnapshotIdentifier: "osc-prod-final-snapshot",
    storageEncrypted: true,
    performanceInsightsEnabled: true,
    tags: { ...tags, Name: "osc-prod-postgres" },
});

// Store credentials in Secrets Manager
const dbSecret = new aws.secretsmanager.Secret("osc-prod-db-secret", {
    description: "OSC prod PostgreSQL credentials",
    recoveryWindowInDays: 7,
    tags,
});

new aws.secretsmanager.SecretVersion("osc-prod-db-secret-version", {
    secretId: dbSecret.id,
    secretString: pulumi.interpolate`{"host":"${db.address}","port":"${db.port}","dbname":"osc","username":"osc_admin","password":"${dbPassword}"}`,
});

// ---------------------------------------------------------------------------
// ECS Fargate with Auto-Scaling (min 2, max 10, 512 CPU / 1024 MB)
// ---------------------------------------------------------------------------

const cluster = new aws.ecs.Cluster("osc-prod-cluster", {
    tags: { ...tags, Name: "osc-prod-cluster" },
});

// CloudWatch log group
const logGroup = new aws.cloudwatch.LogGroup("osc-prod-logs", {
    name: "/ecs/osc-prod",
    retentionInDays: 30,
    tags,
});

// Task execution role
const executionRole = new aws.iam.Role("osc-prod-exec-role", {
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

new aws.iam.RolePolicy("osc-prod-exec-secret-policy", {
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

const taskDefinition = new aws.ecs.TaskDefinition("osc-prod-task", {
    family: "osc-api-prod",
    networkMode: "awsvpc",
    requiresCompatibilities: ["FARGATE"],
    cpu: "512",
    memory: "1024",
    executionRoleArn: executionRole.arn,
    containerDefinitions: pulumi.all([ecrRepository, db.address, db.port, logGroup.name]).apply(
        ([repo, host, port, lgName]) => JSON.stringify([{
            name: "osc-api",
            image: `${repo}/osc-api:${imageTag}`,
            portMappings: [{ containerPort: 8080, protocol: "tcp" }],
            environment: [
                { name: "SPRING_R2DBC_URL", value: `r2dbc:postgresql://${host}:${port}/osc` },
                { name: "SPRING_PROFILES_ACTIVE", value: "prod" },
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
// ALB with HTTPS + HTTP→HTTPS redirect
// ---------------------------------------------------------------------------

const alb = new aws.lb.LoadBalancer("osc-prod-alb", {
    internal: false,
    loadBalancerType: "application",
    securityGroups: [albSg.id],
    subnets: [publicSubnet1.id, publicSubnet2.id, publicSubnet3.id],
    accessLogs: {
        bucket: "osc-prod-alb-logs",
        enabled: false, // Enable after creating the S3 bucket
    },
    tags: { ...tags, Name: "osc-prod-alb" },
});

const targetGroup = new aws.lb.TargetGroup("osc-prod-tg", {
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
    deregistrationDelay: 30,
    tags: { ...tags, Name: "osc-prod-tg" },
});

// HTTP listener → redirect to HTTPS
new aws.lb.Listener("osc-prod-http-listener", {
    loadBalancerArn: alb.arn,
    port: 80,
    protocol: "HTTP",
    defaultActions: [{
        type: "redirect",
        redirect: {
            port: "443",
            protocol: "HTTPS",
            statusCode: "HTTP_301",
        },
    }],
});

// HTTPS listener → forward to target group
new aws.lb.Listener("osc-prod-https-listener", {
    loadBalancerArn: alb.arn,
    port: 443,
    protocol: "HTTPS",
    sslPolicy: "ELBSecurityPolicy-TLS13-1-2-2021-06",
    certificateArn: certValidation.certificateArn,
    defaultActions: [{ type: "forward", targetGroupArn: targetGroup.arn }],
});

// DNS record pointing to the ALB
new aws.route53.Record("osc-prod-dns", {
    zoneId: hostedZoneId,
    name: domainName,
    type: "A",
    aliases: [{
        name: alb.dnsName,
        zoneId: alb.zoneId,
        evaluateTargetHealth: true,
    }],
});

// ECS Service
const service = new aws.ecs.Service("osc-prod-service", {
    cluster: cluster.arn,
    taskDefinition: taskDefinition.arn,
    desiredCount: 2,
    launchType: "FARGATE",
    networkConfiguration: {
        subnets: [privateSubnet1.id, privateSubnet2.id, privateSubnet3.id],
        securityGroups: [appSg.id],
        assignPublicIp: false,
    },
    loadBalancers: [{
        targetGroupArn: targetGroup.arn,
        containerName: "osc-api",
        containerPort: 8080,
    }],
    deploymentCircuitBreaker: {
        enable: true,
        rollback: true,
    },
    tags,
});

// Auto-scaling
const scalingTarget = new aws.appautoscaling.Target("osc-prod-scaling-target", {
    maxCapacity: 10,
    minCapacity: 2,
    resourceId: pulumi.interpolate`service/${cluster.name}/${service.name}`,
    scalableDimension: "ecs:service:DesiredCount",
    serviceNamespace: "ecs",
});

// Scale out when CPU > 70%
new aws.appautoscaling.Policy("osc-prod-cpu-scaling", {
    name: "osc-prod-cpu-scaling",
    policyType: "TargetTrackingScaling",
    resourceId: scalingTarget.resourceId,
    scalableDimension: scalingTarget.scalableDimension,
    serviceNamespace: scalingTarget.serviceNamespace,
    targetTrackingScalingPolicyConfiguration: {
        predefinedMetricSpecification: {
            predefinedMetricType: "ECSServiceAverageCPUUtilization",
        },
        targetValue: 70,
        scaleInCooldown: 300,
        scaleOutCooldown: 60,
    },
});

// Scale out when memory > 75%
new aws.appautoscaling.Policy("osc-prod-memory-scaling", {
    name: "osc-prod-memory-scaling",
    policyType: "TargetTrackingScaling",
    resourceId: scalingTarget.resourceId,
    scalableDimension: scalingTarget.scalableDimension,
    serviceNamespace: scalingTarget.serviceNamespace,
    targetTrackingScalingPolicyConfiguration: {
        predefinedMetricSpecification: {
            predefinedMetricType: "ECSServiceAverageMemoryUtilization",
        },
        targetValue: 75,
        scaleInCooldown: 300,
        scaleOutCooldown: 60,
    },
});

// ---------------------------------------------------------------------------
// WAF v2 — basic rate-limiting rule
// ---------------------------------------------------------------------------

const wafAcl = new aws.wafv2.WebAcl("osc-prod-waf", {
    scope: "REGIONAL",
    description: "OSC prod WAF — rate limiting",
    defaultAction: { allow: {} },
    rules: [
        {
            name: "RateLimitRule",
            priority: 1,
            action: { block: {} },
            statement: {
                rateBasedStatement: {
                    limit: 2000,          // requests per 5-minute window per IP
                    aggregateKeyType: "IP",
                },
            },
            visibilityConfig: {
                cloudwatchMetricsEnabled: true,
                metricName: "osc-prod-rate-limit",
                sampledRequestsEnabled: true,
            },
        },
    ],
    visibilityConfig: {
        cloudwatchMetricsEnabled: true,
        metricName: "osc-prod-waf",
        sampledRequestsEnabled: true,
    },
    tags,
});

// Associate WAF with the ALB
new aws.wafv2.WebAclAssociation("osc-prod-waf-assoc", {
    resourceArn: alb.arn,
    webAclArn: wafAcl.arn,
});

// ---------------------------------------------------------------------------
// Outputs
// ---------------------------------------------------------------------------

export const albDnsName = alb.dnsName;
export const dbEndpoint = db.endpoint;
export const certificateArn = certValidation.certificateArn;
