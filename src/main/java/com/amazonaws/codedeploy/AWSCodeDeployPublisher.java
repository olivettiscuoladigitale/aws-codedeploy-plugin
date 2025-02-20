/*
 * Copyright 2014 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazonaws.codedeploy;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.codedeploy.model.ListApplicationsResult;
import com.amazonaws.services.codedeploy.model.ListDeploymentGroupsRequest;
import com.amazonaws.services.codedeploy.model.ListDeploymentGroupsResult;
import com.amazonaws.services.codedeploy.model.RevisionLocation;
import com.amazonaws.services.codedeploy.model.RevisionLocationType;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.codedeploy.model.BundleType;
import com.amazonaws.services.codedeploy.model.CreateDeploymentRequest;
import com.amazonaws.services.codedeploy.model.CreateDeploymentResult;
import com.amazonaws.services.codedeploy.model.DeploymentInfo;
import com.amazonaws.services.codedeploy.model.DeploymentOverview;
import com.amazonaws.services.codedeploy.model.DeploymentStatus;
import com.amazonaws.services.codedeploy.model.GetDeploymentRequest;
import com.amazonaws.services.codedeploy.model.RegisterApplicationRevisionRequest;
import com.amazonaws.services.codedeploy.model.S3Location;

import hudson.AbortException;
import hudson.FilePath;
import hudson.FilePath.TarCompression;
import hudson.Launcher;
import hudson.Util;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Publisher;
import hudson.util.DirScanner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.io.FileUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;

/**
 * The AWS CodeDeploy Publisher is a post-build plugin that adds the ability to start a new CodeDeploy deployment
 * with the project's workspace as the application revision.
 * <p>
 * To configure, users must create an IAM role that allows "S3" and "CodeDeploy" actions and must be assumable by
 * the globally configured keys. This allows the plugin to get temporary credentials instead of requiring permanent
 * credentials to be configured for each project.
 */
public class AWSCodeDeployPublisher extends Publisher implements SimpleBuildStep {

    public static final long DEFAULT_TIMEOUT_SECONDS = 900;
    public static final long DEFAULT_POLLING_FREQUENCY_SECONDS = 15;
    public static final String ROLE_SESSION_NAME = "jenkins-codedeploy-plugin";
    private static final Regions[] AVAILABLE_REGIONS = {Regions.AP_NORTHEAST_1, Regions.AP_NORTHEAST_2, Regions.AP_SOUTH_1,Regions.AP_SOUTHEAST_1, Regions.AP_SOUTHEAST_2, Regions.CA_CENTRAL_1, Regions.CN_NORTH_1, Regions.EU_CENTRAL_1, Regions.EU_SOUTH_1, Regions.EU_WEST_1, Regions.EU_WEST_2, Regions.SA_EAST_1, Regions.US_EAST_2, Regions.US_EAST_1, Regions.US_WEST_1, Regions.US_WEST_2};

    private final String s3bucket;
    private final String s3prefix;
    private final String applicationName;
    private final String deploymentGroupName; // TODO allow for deployment to multiple groups
    private final String deploymentConfig;
    private final Long pollingTimeoutSec;
    private final Long pollingFreqSec;

    private final boolean deploymentGroupAppspec;
    private final boolean waitForCompletion;
    private final String externalId;
    private final String iamRoleArn;
    private final String region;
    private final String includes;
    private final String excludes;
    private final String subdirectory;
    private final String proxyHost;
    private final int proxyPort;
    private final String fileType;

    private final String awsAccessKey;
    private final Secret awsSecretKey;
    private final String credentials;
    private final String deploymentMethod;
    private final String packArtifacts;
    private final String versionFileName;

    @Deprecated
    private transient PrintStream logger;
    @Deprecated
    private transient Map<String, String> envVars;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public AWSCodeDeployPublisher(
            String s3bucket,
            String s3prefix,
            String applicationName,
            String deploymentGroupName,
            String deploymentConfig,
            String region,
            Boolean deploymentGroupAppspec,
            Boolean waitForCompletion,
            Long pollingTimeoutSec,
            Long pollingFreqSec,
            String credentials,
            String versionFileName,
            String packArtifacts,
            String deploymentMethod,
            String awsAccessKey,
            String awsSecretKey,
            String iamRoleArn,
            String externalId,
            String includes,
            String proxyHost,
            int proxyPort,
            String excludes,
            String subdirectory,
            String fileType) {

        this.externalId = externalId;
        this.applicationName = applicationName;
        this.deploymentGroupName = deploymentGroupName;
        if (deploymentConfig != null && deploymentConfig.length() == 0) {
            this.deploymentConfig = null;
        } else {
            this.deploymentConfig = deploymentConfig;
        }
        this.region = region;
        this.includes = includes;
        this.excludes = excludes;
        this.subdirectory = subdirectory;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.fileType = fileType;
        this.credentials = credentials;
        this.deploymentMethod = deploymentMethod;
        this.packArtifacts = packArtifacts;
        this.versionFileName = versionFileName;
        this.awsAccessKey = awsAccessKey;
        this.awsSecretKey = Secret.fromString(awsSecretKey);
        this.iamRoleArn = iamRoleArn;
        this.deploymentGroupAppspec = deploymentGroupAppspec;

        if (waitForCompletion != null && waitForCompletion) {
            this.waitForCompletion = waitForCompletion;
            if (pollingTimeoutSec == null) {
                this.pollingTimeoutSec = DEFAULT_TIMEOUT_SECONDS;
            } else {
                this.pollingTimeoutSec = pollingTimeoutSec;
            }
            if (pollingFreqSec == null) {
                this.pollingFreqSec = DEFAULT_POLLING_FREQUENCY_SECONDS;
            } else {
                this.pollingFreqSec = pollingFreqSec;
            }
        } else {
            this.waitForCompletion = false;
            this.pollingTimeoutSec = null;
            this.pollingFreqSec = null;
        }

        this.s3bucket = s3bucket;
        if (s3prefix == null || s3prefix.equals("/") || s3prefix.length() == 0) {
            this.s3prefix = "";
        } else {
            this.s3prefix = s3prefix;
        }
    }

    @Override
    public void perform(@Nonnull Run<?, ?> build, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws IOException, InterruptedException {
        final PrintStream logger = listener.getLogger();
        final Map<String, String> envVars = build.getEnvironment(listener);

        //	Print envVars
        System.out.println("Print envVars (System.out)");
        envVars.forEach((key, value) -> System.out.println(key + ":" + value));
        logger.println("Print envVars (logger)");
        envVars.forEach((key, value) -> logger.println(key + ":" + value));

        final boolean buildFailed = build.getResult() == Result.FAILURE;
        if (buildFailed) {
            logger.println("Skipping CodeDeploy publisher as build failed");
            return;
        }

        final AWSClients aws;
        if ("awsAccessKey".equals(credentials)) {
            if (StringUtils.isEmpty(this.awsAccessKey) && StringUtils.isEmpty(Secret.toString(this.awsSecretKey))) {
                aws = AWSClients.fromDefaultCredentialChain(
                        this.region,
                        this.proxyHost,
                        this.proxyPort);
            } else {
                aws = AWSClients.fromBasicCredentials(
                        this.region,
                        this.awsAccessKey,
                        Secret.toString(this.awsSecretKey),
                        this.proxyHost,
                        this.proxyPort);
            }
        } else {
            aws = AWSClients.fromIAMRole(
                    this.region,
                    this.iamRoleArn,
                    this.getDescriptor().getExternalId(),
                    this.proxyHost,
                    this.proxyPort);
        }

        boolean success = false;

        try {

            verifyCodeDeployApplication(aws, envVars);

            final String projectName = build.getDisplayName();
            if (workspace == null) {
                throw new IllegalArgumentException("No workspace present for the build.");
            }

            final FilePath sourceDirectory = getSourceDirectory(workspace, envVars);
            final RevisionLocation revisionLocation;
            boolean s3BuildFileExist = false;
            if ("s3Direct".equals(packArtifacts)) {
                revisionLocation = new RevisionLocation();
                try {
                    s3BuildFileExist = aws.s3.doesObjectExist(getS3bucket(), getS3PrefixFromEnv(envVars));
                    if (!s3BuildFileExist) {
                        throw new Exception("Path on s3 does not exists! 's3://" + getS3bucket() + "/" + getS3PrefixFromEnv(envVars) + "'");
                    }
                    S3Location s3Location = new S3Location();
                    s3Location.setBucket(getS3bucket());
                    s3Location.setKey(getS3PrefixFromEnv(envVars));
                    s3Location.setBundleType(BundleType.Tgz);
                    // s3Location.setETag(s3result.getETag());

                    revisionLocation.setRevisionType(RevisionLocationType.S3);
                    revisionLocation.setS3Location(s3Location);
                } catch (Exception e) {
                    logger.println("Failed to build s3 location from s3 directly 's3://" + getS3bucket() + "/" + getS3PrefixFromEnv(envVars) +
                            "'; exception follows.");
                    logger.println(e.getMessage());
                    e.printStackTrace(logger);
                }
            } else {
                revisionLocation = compressAndUpload(aws, projectName, sourceDirectory, this.fileType);
            }
            



            registerRevision(aws, revisionLocation, logger, envVars);
            if ("onlyRevision".equals(deploymentMethod)) {
                success = true;
            } else {

                String deploymentId = createDeployment(aws, revisionLocation, logger, envVars);

                success = waitForDeployment(aws, deploymentId, logger);
            }

        } catch (Exception e) {

            logger.println("Failed CodeDeploy post-build step; exception follows.");
            logger.println(e.getMessage());
            e.printStackTrace(logger);
        }

        if (!success) {
            throw new AbortException();
        }
    }

    private FilePath getSourceDirectory(FilePath basePath, Map<String, String> envVars) throws IOException, InterruptedException {
        String subdirectory = StringUtils.trimToEmpty(getSubdirectoryFromEnv(envVars));
        if (!subdirectory.isEmpty() && !subdirectory.startsWith("/")) {
            subdirectory = "/" + subdirectory;
        }
        FilePath sourcePath = basePath.withSuffix(subdirectory).absolutize();
        if (!sourcePath.isDirectory() || !isSubDirectory(basePath, sourcePath)) {
            throw new IllegalArgumentException("Provided path (resolved as '" + sourcePath
                    + "') is not a subdirectory of the workspace (resolved as '" + basePath + "')");
        }
        return sourcePath;
    }

    private boolean isSubDirectory(FilePath parent, FilePath child) {
        FilePath parentFolder = child;
        while (parentFolder != null) {
            if (parent.equals(parentFolder)) {
                return true;
            }
            parentFolder = parentFolder.getParent();
        }
        return false;
    }

    private void verifyCodeDeployApplication(AWSClients aws, Map<String, String> envVars) throws IllegalArgumentException {
        // Check that the application exists
        ListApplicationsResult applications = aws.codedeploy.listApplications();
        String applicationName = getApplicationNameFromEnv(envVars);
        String deploymentGroupName = getDeploymentGroupNameFromEnv(envVars);


        System.out.println("Applications found: " + Arrays.toString(applications.getApplications().toArray()));

        if (!applications.getApplications().contains(applicationName)) {
            throw new IllegalArgumentException("Cannot find application named '" + applicationName + "'");
        }

        // Check that the deployment group exists
        ListDeploymentGroupsResult deploymentGroups = aws.codedeploy.listDeploymentGroups(
                new ListDeploymentGroupsRequest()
                        .withApplicationName(applicationName)
        );

        if (!deploymentGroups.getDeploymentGroups().contains(deploymentGroupName)) {
            throw new IllegalArgumentException("Cannot find deployment group named '" + deploymentGroupName + "'");
        }
    }

private RevisionLocation compressAndUpload(AWSClients aws, String projectName, FilePath sourceDirectory, String fileType) throws IOException, InterruptedException, IllegalArgumentException {

		String extension;
		BundleType bundleType;
		if (fileType == null || fileType.equals("Tar")) {
        	extension = ".zip";
            bundleType = BundleType.Zip;
        } else if (fileType.equals("Tar")) {
            extension = ".tar";
            bundleType = BundleType.Tar;
        } else if (fileType.equals("Tgz")) {
            extension = ".tar.gz";
            bundleType = BundleType.Tgz;
        } else {
        	extension = ".zip";
            bundleType = BundleType.Zip;
        }

        File tarzipFile = File.createTempFile(projectName + "-", extension);
        String key;
        File appspec;
        File dest;
        String deploymentGroupName = getDeploymentGroupNameFromEnv(envVars);
        String prefix = getS3PrefixFromEnv(envVars);
        String bucket = getS3BucketFromEnv(envVars);

        if (bucket.indexOf("/") > 0) {
            throw new IllegalArgumentException("S3 Bucket field cannot contain any subdirectories.  Bucket name only!");
        }

        try {
            if (this.deploymentGroupAppspec) {
                appspec = new File(sourceDirectory + "/appspec." + deploymentGroupName + ".yml");
                if (appspec.exists()) {
                    dest = new File(sourceDirectory + "/appspec.yml");
                    FileUtils.copyFile(appspec, dest);
                    logger.println("Use appspec." + deploymentGroupName + ".yml");
                }
                if (!appspec.exists()) {
                    throw new IllegalArgumentException("/appspec." + deploymentGroupName + ".yml file does not exist");
                }

            }


            logger.println("package files into " + tarzipFile.getAbsolutePath());

			if (fileType == null || fileType.equals("Zip")) {
	        	sourceDirectory.zip(
	                    new FileOutputStream(tarzipFile),
	                    new DirScanner.Glob(this.includes, this.excludes)
		        );
	        } else if (fileType.equals("Tar")) {
	            sourceDirectory.tar(
                    new FileOutputStream(tarzipFile),
                    new DirScanner.Glob(this.includes, this.excludes)
            	);
	        } else if (fileType.equals("Tgz")) {
	            sourceDirectory.tar(
                    TarCompression.GZIP.compress(new FileOutputStream(tarzipFile)),
                    new DirScanner.Glob(this.includes, this.excludes)
            	);
	        } else {
	        	sourceDirectory.zip(
	                    new FileOutputStream(tarzipFile),
	                    new DirScanner.Glob(this.includes, this.excludes)
		        );
	        }

            if (prefix.isEmpty()) {
                key = tarzipFile.getName();
            } else {
                key = Util.replaceMacro(prefix, envVars);
                if (prefix.endsWith("/")) {
                    key += tarzipFile.getName();
                } else {
                    key += "/" + tarzipFile.getName();
                }
            }
            logger.println("Uploading zip to s3://" + bucket + "/" + key);
            PutObjectResult s3result = aws.s3.putObject(bucket, key, tarzipFile);

            S3Location s3Location = new S3Location();
            s3Location.setBucket(bucket);
            s3Location.setKey(key);
            s3Location.setBundleType(bundleType);
            s3Location.setETag(s3result.getETag());

            RevisionLocation revisionLocation = new RevisionLocation();
            revisionLocation.setRevisionType(RevisionLocationType.S3);
            revisionLocation.setS3Location(s3Location);

            return revisionLocation;
        } finally {
            final boolean deleted = tarzipFile.delete();
            if (!deleted) {
                logger.println("Failed to clean up file " + tarzipFile.getPath());
            }
            tarzipFile.delete();
        }
    }

    private void registerRevision(AWSClients aws, RevisionLocation revisionLocation, PrintStream logger, Map<String, String> envVars) {

        String applicationName = getApplicationNameFromEnv(envVars);
        logger.println("Registering revision for application '" + applicationName + "'");

        aws.codedeploy.registerApplicationRevision(
                new RegisterApplicationRevisionRequest()
                        .withApplicationName(applicationName)
                        .withRevision(revisionLocation)
                        .withDescription("Application revision registered via Jenkins")
        );
    }

    private String createDeployment(AWSClients aws, RevisionLocation revisionLocation, PrintStream logger, Map<String, String> envVars) throws Exception {

        logger.println("Creating deployment with revision at " + revisionLocation);

        CreateDeploymentResult createDeploymentResult = aws.codedeploy.createDeployment(
                new CreateDeploymentRequest()
                        .withDeploymentConfigName(getDeploymentConfigFromEnv(envVars))
                        .withDeploymentGroupName(getDeploymentGroupNameFromEnv(envVars))
                        .withApplicationName(getApplicationNameFromEnv(envVars))
                        .withRevision(revisionLocation)
                        .withDescription("Deployment created by Jenkins")
        );

        return createDeploymentResult.getDeploymentId();
    }

    private boolean waitForDeployment(AWSClients aws, String deploymentId, PrintStream logger) throws InterruptedException {

        if (!this.waitForCompletion) {
            return true;
        }

        logger.println("Monitoring deployment with ID " + deploymentId + "...");
        GetDeploymentRequest deployInfoRequest = new GetDeploymentRequest();
        deployInfoRequest.setDeploymentId(deploymentId);

        DeploymentInfo deployStatus = aws.codedeploy.getDeployment(deployInfoRequest).getDeploymentInfo();

        long startTimeMillis;
        if (deployStatus == null || deployStatus.getStartTime() == null) {
            startTimeMillis = new Date().getTime();
        } else {
            startTimeMillis = deployStatus.getStartTime().getTime();
        }

        boolean success = true;
        long pollingTimeoutMillis = this.pollingTimeoutSec * 1000L;
        long pollingFreqMillis = this.pollingFreqSec * 1000L;

        while (deployStatus == null || deployStatus.getCompleteTime() == null) {

            if (deployStatus == null) {
                logger.println("Deployment status: unknown.");
            } else {
                DeploymentOverview overview = deployStatus.getDeploymentOverview();
                logger.println("Deployment status: " + deployStatus.getStatus() + "; instances: " + overview);
            }

            deployStatus = aws.codedeploy.getDeployment(deployInfoRequest).getDeploymentInfo();
            Date now = new Date();

            if (now.getTime() - startTimeMillis >= pollingTimeoutMillis) {
                logger.println("Exceeded maximum polling time of " + pollingTimeoutMillis + " milliseconds.");
                success = false;
                break;
            }

            Thread.sleep(pollingFreqMillis);
        }

        logger.println("Deployment status: " + deployStatus.getStatus() + "; instances: " + deployStatus.getDeploymentOverview());

        if (!deployStatus.getStatus().equals(DeploymentStatus.Succeeded.toString())) {
            logger.println("Deployment did not succeed. Final status: " + deployStatus.getStatus());
            success = false;
        }

        return success;
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {

        return (DescriptorImpl) super.getDescriptor();
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    /**
     * Descriptor for {@link AWSCodeDeployPublisher}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     * <p>
     * See <tt>src/main/resources/com/amazonaws/codedeploy/AWSCodeDeployPublisher/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private String externalId;
        private String awsAccessKey;
        private Secret awsSecretKey;
        private String proxyHost;
        private int proxyPort;

        /**
         * In order to load the persisted global configuration, you have to
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();

            if (externalId == null) {
                setExternalId(UUID.randomUUID().toString());
            }
        }

        public FormValidation doCheckName(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please add the appropriate values");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Deploy an application to AWS CodeDeploy";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {

            awsAccessKey = formData.getString("awsAccessKey");
            awsSecretKey = Secret.fromString(formData.getString("awsSecretKey"));
            proxyHost = formData.getString("proxyHost");
            proxyPort = Integer.parseInt(formData.getString("proxyPort"));

            req.bindJSON(this, formData);
            save();
            return super.configure(req, formData);
        }

        public String getExternalId() {
            return externalId;
        }

        public void setExternalId(String externalId) {
            this.externalId = externalId;
        }

        public void setProxyHost(String proxyHost) {
            this.proxyHost = proxyHost;
        }

        public String getProxyHost() {
            return proxyHost;
        }

        public void setProxyPort(int proxyPort) {
            this.proxyPort = proxyPort;
        }

        public int getProxyPort() {
            return proxyPort;
        }

        public String getAccountId() {
            return AWSClients.getAccountId(getProxyHost(), getProxyPort());
        }

        public FormValidation doTestConnection(
                @QueryParameter String s3bucket,
                @QueryParameter String applicationName,
                @QueryParameter String region,
                @QueryParameter String iamRoleArn,
                @QueryParameter String proxyHost,
                @QueryParameter int proxyPort) {

            System.out.println("Testing connection with parameters: "
                    + s3bucket + ","
                    + applicationName + ","
                    + region + ","
                    + iamRoleArn + ","
                    + this.externalId + ","
                    + proxyHost + ","
                    + proxyPort
            );

            try {
                AWSClients awsClients = AWSClients.fromIAMRole(region, iamRoleArn, this.externalId, proxyHost, proxyPort);
                awsClients.testConnection(s3bucket, applicationName);
            } catch (Exception e) {
                return FormValidation.error("Connection test failed with error: " + e.getMessage());
            }

            return FormValidation.ok("Connection test passed.");
        }

        public ListBoxModel doFillRegionItems() {
            ListBoxModel items = new ListBoxModel();
            for (Regions region : AVAILABLE_REGIONS) {
                items.add(region.toString(), region.getName());
            }
            return items;
        }

        public String getAwsSecretKey() {
            return Secret.toString(awsSecretKey);
        }

        public void setAwsSecretKey(String awsSecretKey) {
            this.awsSecretKey = Secret.fromString(awsSecretKey);
        }

        public String getAwsAccessKey() {
            return awsAccessKey;
        }

        public void setAwsAccessKey(String awsAccessKey) {
            this.awsAccessKey = awsAccessKey;
        }

    }

    public String getApplicationName() {
        return applicationName;
    }

    public String getDeploymentGroupName() {
        return deploymentGroupName;
    }

    public String getDeploymentConfig() {
        return deploymentConfig;
    }

    public String getS3bucket() {
        return s3bucket;
    }

    public String getS3prefix() {
        return s3prefix;
    }

    public Long getPollingTimeoutSec() {
        return pollingTimeoutSec;
    }

    public String getIamRoleArn() {
        return iamRoleArn;
    }

    public String getAwsAccessKey() {
        return awsAccessKey;
    }

    public Secret getAwsSecretKey() {
        return awsSecretKey;
    }

    public Long getPollingFreqSec() {
        return pollingFreqSec;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getDeploymentMethod() {
        return deploymentMethod;
    }

    public String getPackArtifacts() {
        return packArtifacts;
    }

    public String getVersionFileName() {
        return versionFileName;
    }

    public boolean getWaitForCompletion() {
        return waitForCompletion;
    }

    public boolean getDeploymentGroupAppspec() {
        return deploymentGroupAppspec;
    }

    public String getCredentials() {
        return credentials;
    }

    public String getIncludes() {
        return includes;
    }

    public String getExcludes() {
        return excludes;
    }

    public String getSubdirectory() {
        return subdirectory;
    }

    public String getRegion() {
        return region;
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public String getApplicationNameFromEnv(final Map<String, String> envVars) {
        return Util.replaceMacro(this.applicationName, envVars);
    }

    public String getDeploymentGroupNameFromEnv(final Map<String, String> envVars) {
        return Util.replaceMacro(this.deploymentGroupName, envVars);
    }

    public String getDeploymentConfigFromEnv(final Map<String, String> envVars) {
        return Util.replaceMacro(this.deploymentConfig, envVars);
    }

    public String getS3BucketFromEnv(final Map<String, String> envVars) {
        return Util.replaceMacro(this.s3bucket, envVars);
    }

    public String getS3PrefixFromEnv(Map<String, String> envVars) {
        return Util.replaceMacro(this.s3prefix, envVars);
    }

    public String getSubdirectoryFromEnv(Map<String, String> envVars) {
        return Util.replaceMacro(this.subdirectory, envVars);
    }
}
