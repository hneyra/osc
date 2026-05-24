import * as pulumi from "@pulumi/pulumi";
import * as aws from "@pulumi/aws";

export interface ComputeStackArgs {
    env: string;
    vpcId: pulumi.Input<string>;
    subnetIds: pulumi.Input<string[]>;
    publicSubnetIds: pulumi.Input<string[]>;
    containerRegistryUrl: pulumi.Input<string>;
    databaseUrl: pulumi.Input<string>;
    databaseSecretArn: pulumi.Input<string>;
}

export class ComputeStack extends pulumi.ComponentResource {
    public readonly apiEndpoint: pulumi.Output<string>;

    constructor(name: string, args: ComputeStackArgs, opts?: pulumi.ComponentResourceOptions) {
        super("osc:compute:ComputeStack", name, {}, opts);

        const tags = { Environment: args.env, Project: "osc" };
        const isProd = args.env === "prod";

        // ECS Cluster
        const cluster = new aws.ecs.Cluster(`${name}-cluster`, { tags }, { parent: this });

        // Task execution role
        const executionRole = new aws.iam.Role(`${name}-exec-role`, {
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
        }, { parent: this });

        // Allow reading DB secret
        new aws.iam.RolePolicy(`${name}-secret-policy`, {
            role: executionRole.id,
            policy: pulumi.interpolate`{
                "Version": "2012-10-17",
                "Statement": [{
                    "Effect": "Allow",
                    "Action": ["secretsmanager:GetSecretValue"],
                    "Resource": "${args.databaseSecretArn}"
                }]
            }`,
        }, { parent: this });

        // Task definition
        const taskDef = new aws.ecs.TaskDefinition(`${name}-task`, {
            family: `osc-api-${args.env}`,
            networkMode: "awsvpc",
            requiresCompatibilities: ["FARGATE"],
            cpu: isProd ? "1024" : "256",
            memory: isProd ? "2048" : "512",
            executionRoleArn: executionRole.arn,
            containerDefinitions: pulumi.interpolate`[{
                "name": "osc-api",
                "image": "${args.containerRegistryUrl}/osc-api:latest",
                "portMappings": [{ "containerPort": 8080, "protocol": "tcp" }],
                "environment": [
                    { "name": "SPRING_R2DBC_URL", "value": "${args.databaseUrl}" },
                    { "name": "SPRING_PROFILES_ACTIVE", "value": "${args.env}" }
                ],
                "logConfiguration": {
                    "logDriver": "awslogs",
                    "options": {
                        "awslogs-group": "/ecs/osc-api",
                        "awslogs-region": "us-east-1",
                        "awslogs-stream-prefix": "ecs"
                    }
                }
            }]`,
            tags,
        }, { parent: this });

        // ALB
        const alb = new aws.lb.LoadBalancer(`${name}-alb`, {
            internal: false,
            loadBalancerType: "application",
            subnets: args.publicSubnetIds,
            tags,
        }, { parent: this });

        const targetGroup = new aws.lb.TargetGroup(`${name}-tg`, {
            port: 8080,
            protocol: "HTTP",
            targetType: "ip",
            vpcId: args.vpcId,
            healthCheck: { path: "/actuator/health", interval: 30 },
            tags,
        }, { parent: this });

        new aws.lb.Listener(`${name}-listener`, {
            loadBalancerArn: alb.arn,
            port: 80,
            protocol: "HTTP",
            defaultActions: [{ type: "forward", targetGroupArn: targetGroup.arn }],
        }, { parent: this });

        // ECS Service
        new aws.ecs.Service(`${name}-service`, {
            cluster: cluster.arn,
            taskDefinition: taskDef.arn,
            desiredCount: isProd ? 2 : 1,
            launchType: "FARGATE",
            networkConfiguration: {
                subnets: args.subnetIds,
                assignPublicIp: false,
            },
            loadBalancers: [{
                targetGroupArn: targetGroup.arn,
                containerName: "osc-api",
                containerPort: 8080,
            }],
            tags,
        }, { parent: this });

        this.apiEndpoint = pulumi.interpolate`http://${alb.dnsName}`;
        this.registerOutputs({ apiEndpoint: this.apiEndpoint });
    }
}
