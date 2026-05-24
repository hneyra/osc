import * as pulumi from "@pulumi/pulumi";
import * as aws from "@pulumi/aws";

export interface DatabaseStackArgs {
    env: string;
    vpcId: pulumi.Input<string>;
    subnetIds: pulumi.Input<string[]>;
    securityGroupId: pulumi.Input<string>;
}

export class DatabaseStack extends pulumi.ComponentResource {
    public readonly endpoint: pulumi.Output<string>;
    public readonly connectionUrl: pulumi.Output<string>;
    public readonly secretArn: pulumi.Output<string>;

    constructor(name: string, args: DatabaseStackArgs, opts?: pulumi.ComponentResourceOptions) {
        super("osc:database:DatabaseStack", name, {}, opts);

        const config = new pulumi.Config();
        const dbPassword = config.requireSecret("dbPassword");
        const tags = { Environment: args.env, Project: "osc" };

        // Subnet group for RDS
        const subnetGroup = new aws.rds.SubnetGroup(`${name}-subnet-group`, {
            subnetIds: args.subnetIds,
            tags,
        }, { parent: this });

        // PostgreSQL 16 RDS instance
        const isProd = args.env === "prod";
        const db = new aws.rds.Instance(`${name}-postgres`, {
            engine: "postgres",
            engineVersion: "16.3",
            instanceClass: isProd ? "db.t3.medium" : "db.t3.micro",
            allocatedStorage: isProd ? 100 : 20,
            storageType: "gp3",
            dbName: "osc",
            username: "osc_admin",
            password: dbPassword,
            dbSubnetGroupName: subnetGroup.name,
            vpcSecurityGroupIds: [args.securityGroupId],
            multiAz: isProd,
            backupRetentionPeriod: isProd ? 7 : 1,
            deletionProtection: isProd,
            skipFinalSnapshot: !isProd,
            finalSnapshotIdentifier: isProd ? `${name}-final-snapshot` : undefined,
            tags,
        }, { parent: this });

        // Store credentials in Secrets Manager
        const secret = new aws.secretsmanager.Secret(`${name}-db-secret`, {
            description: "OSC PostgreSQL credentials",
            tags,
        }, { parent: this });

        new aws.secretsmanager.SecretVersion(`${name}-db-secret-version`, {
            secretId: secret.id,
            secretString: pulumi.interpolate`{
                "host": "${db.address}",
                "port": "${db.port}",
                "dbname": "osc",
                "username": "osc_admin",
                "password": "${dbPassword}"
            }`,
        }, { parent: this });

        this.endpoint = db.endpoint;
        this.connectionUrl = pulumi.interpolate`r2dbc:postgresql://${db.address}:${db.port}/osc`;
        this.secretArn = secret.arn;

        this.registerOutputs({
            endpoint: this.endpoint,
            connectionUrl: this.connectionUrl,
            secretArn: this.secretArn,
        });
    }
}
