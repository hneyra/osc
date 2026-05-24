import * as pulumi from "@pulumi/pulumi";
import { DatabaseStack } from "./src/database";
import { NetworkingStack } from "./src/networking";
import { ComputeStack } from "./src/compute";

const config = new pulumi.Config();
const env = pulumi.getStack(); // "dev" | "prod"

// Reference existing shared infrastructure from hneyra/iaac
// These resources are already provisioned — we only reference their outputs.
const iaacStack = new pulumi.StackReference(`hneyra/iaac/${env}`);

// Shared outputs from iaac
export const sharedVpcId = iaacStack.getOutput("vpcId");
export const sharedPrivateSubnetIds = iaacStack.getOutput("privateSubnetIds");
export const sharedPublicSubnetIds = iaacStack.getOutput("publicSubnetIds");
export const sharedContainerRegistryUrl = iaacStack.getOutput("containerRegistryUrl");

// Provision OSC-specific infrastructure
const networking = new NetworkingStack("osc-networking", {
    env,
    vpcId: sharedVpcId,
});

const database = new DatabaseStack("osc-database", {
    env,
    vpcId: sharedVpcId,
    subnetIds: sharedPrivateSubnetIds,
    securityGroupId: networking.dbSecurityGroupId,
});

const compute = new ComputeStack("osc-compute", {
    env,
    vpcId: sharedVpcId,
    subnetIds: sharedPrivateSubnetIds,
    publicSubnetIds: sharedPublicSubnetIds,
    containerRegistryUrl: sharedContainerRegistryUrl,
    databaseUrl: database.connectionUrl,
    databaseSecretArn: database.secretArn,
});

// Exported outputs
export const apiEndpoint = compute.apiEndpoint;
export const databaseEndpoint = database.endpoint;
export const containerRegistryUrl = sharedContainerRegistryUrl;
