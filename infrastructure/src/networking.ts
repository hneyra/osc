import * as pulumi from "@pulumi/pulumi";
import * as aws from "@pulumi/aws";

export interface NetworkingStackArgs {
    env: string;
    vpcId: pulumi.Input<string>;
}

export class NetworkingStack extends pulumi.ComponentResource {
    public readonly dbSecurityGroupId: pulumi.Output<string>;
    public readonly appSecurityGroupId: pulumi.Output<string>;

    constructor(name: string, args: NetworkingStackArgs, opts?: pulumi.ComponentResourceOptions) {
        super("osc:networking:NetworkingStack", name, {}, opts);

        const tags = { Environment: args.env, Project: "osc" };

        // Security group for the application tier
        const appSg = new aws.ec2.SecurityGroup(`${name}-app-sg`, {
            vpcId: args.vpcId,
            description: "OSC application tier",
            ingress: [
                { protocol: "tcp", fromPort: 8080, toPort: 8080, cidrBlocks: ["0.0.0.0/0"], description: "HTTP API" },
            ],
            egress: [
                { protocol: "-1", fromPort: 0, toPort: 0, cidrBlocks: ["0.0.0.0/0"], description: "All outbound" },
            ],
            tags,
        }, { parent: this });

        // Security group for PostgreSQL — only from app tier
        const dbSg = new aws.ec2.SecurityGroup(`${name}-db-sg`, {
            vpcId: args.vpcId,
            description: "OSC PostgreSQL — app tier only",
            ingress: [
                {
                    protocol: "tcp",
                    fromPort: 5432,
                    toPort: 5432,
                    securityGroups: [appSg.id],
                    description: "PostgreSQL from app tier",
                },
            ],
            egress: [
                { protocol: "-1", fromPort: 0, toPort: 0, cidrBlocks: ["0.0.0.0/0"], description: "All outbound" },
            ],
            tags,
        }, { parent: this });

        this.dbSecurityGroupId = dbSg.id;
        this.appSecurityGroupId = appSg.id;

        this.registerOutputs({
            dbSecurityGroupId: this.dbSecurityGroupId,
            appSecurityGroupId: this.appSecurityGroupId,
        });
    }
}
