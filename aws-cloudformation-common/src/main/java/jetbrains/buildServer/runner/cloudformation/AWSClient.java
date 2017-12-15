/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.runner.cloudformation;

import com.amazonaws.AmazonClientException;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient;
import com.amazonaws.services.cloudformation.model.CreateStackRequest;
import com.amazonaws.services.cloudformation.model.DeleteStackRequest;
import com.amazonaws.services.cloudformation.model.DescribeStackEventsRequest;
import com.amazonaws.services.cloudformation.model.DescribeStackEventsResult;
import com.amazonaws.services.cloudformation.model.DescribeStacksRequest;
import com.amazonaws.services.cloudformation.model.DescribeStacksResult;
import com.amazonaws.services.cloudformation.model.Parameter;
import com.amazonaws.services.cloudformation.model.Stack;
import com.amazonaws.services.cloudformation.model.StackEvent;
import com.amazonaws.services.cloudformation.model.StackStatus;
import com.amazonaws.services.cloudformation.model.UpdateStackRequest;
import com.amazonaws.services.cloudformation.model.ValidateTemplateRequest;
import jetbrains.buildServer.util.amazon.AWSClients;
import jetbrains.buildServer.util.amazon.AWSException;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joda.time.Seconds;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

class AWSClient {

    @NotNull
    private AmazonCloudFormationClient myCloudFormationClient;
    @NotNull
    private Listener myListener = new Listener();

    AWSClient(@NotNull AWSClients clients) {
        myCloudFormationClient = clients.createCloudFormationClient();
    }

    @NotNull
    AWSClient withDescription(@NotNull String description) {
        return this;
    }

    @NotNull
    AWSClient withListener(@NotNull Listener listener) {
        myListener = listener;
        return this;
    }

    /**
     * Uploads application revision archive to S3 bucket named s3BucketName with
     * the provided key and bundle type.
     * <p>
     * For performing this operation target AWSClient must have corresponding S3
     * permissions.
     */
    void initiateCFN(@NotNull String stackName,
                     @NotNull String region,
                     @NotNull String s3BucketName,
                     @NotNull String s3ObjectKey,
                     @NotNull String cfnAction,
                     @NotNull String onFailure,
                     @NotNull Map<String, String> systemProperties
    ) {
        try {
            Region reg = Region.getRegion(Regions.fromName(region));
            myCloudFormationClient.setRegion(reg);

            String templateURL;
            templateURL = getTemplateUrl(reg, s3BucketName, s3ObjectKey);

            if (cfnAction.equalsIgnoreCase("Create") || cfnAction.equalsIgnoreCase("Update")) {
                ArrayList<String> capabilities = new ArrayList<>();
                capabilities.add("CAPABILITY_NAMED_IAM");

                if (doesStackExist(stackName)) {
                    myListener.updateInProgress(stackName);

                    UpdateStackRequest updateStackRequest = new UpdateStackRequest();
                    updateStackRequest.setStackName(stackName);
                    updateStackRequest.setTemplateURL(templateURL);

                    List<Parameter> parameters = convertSystemProperties(systemProperties);
                    updateStackRequest.setParameters(parameters);
                    updateStackRequest.setCapabilities(capabilities);

                    myCloudFormationClient.updateStack(updateStackRequest);
                    waitForCompletion(myCloudFormationClient, stackName);
                } else {
                    myListener.createStackStarted(stackName, region, s3BucketName, s3ObjectKey, cfnAction);
                    CreateStackRequest createRequest = new CreateStackRequest();

                    createRequest.setStackName(stackName);
                    createRequest.setTemplateURL(templateURL);
                    createRequest.setOnFailure(onFailure);

                    List<Parameter> parameters = convertSystemProperties(systemProperties);
                    createRequest.setParameters(parameters);
                    createRequest.setCapabilities(capabilities);

                    myCloudFormationClient.createStack(createRequest);
                    waitForCompletion(myCloudFormationClient, stackName);
                }

            } else if (cfnAction.equalsIgnoreCase("Delete")) {
                myListener.deleteStarted(stackName, region);
                DeleteStackRequest deleteStackRequest = new DeleteStackRequest();
                deleteStackRequest.setStackName(stackName);
                myCloudFormationClient.deleteStack(deleteStackRequest);
                waitForDelete(myCloudFormationClient, stackName);

            } else if (cfnAction.equalsIgnoreCase("Validate")) {
                myListener.validateStarted(stackName);
                ValidateTemplateRequest validatetempRequest = new ValidateTemplateRequest();
                validatetempRequest.setTemplateURL(templateURL);
                myListener.validateFinished(myCloudFormationClient.validateTemplate(validatetempRequest).getParameters().toString());
            }
        } catch (Throwable t) {
            processFailure(t);
        }
    }

    private boolean doesStackExist(@NotNull String stackName) {
        DescribeStacksRequest dsr = new DescribeStacksRequest();
        dsr.setStackName(stackName);
        try {
            DescribeStacksResult describeStacksResult;
            describeStacksResult = myCloudFormationClient.describeStacks(dsr);
            if (describeStacksResult.getStacks().isEmpty()) {
                return false;
            } else {
                boolean stackExists = false;
                for (Stack stack : describeStacksResult.getStacks()) {
                    if (statusIsCompleted(stack.getStackStatus())) {
                        stackExists = true;
                    }
                }
                return stackExists;
            }
        } catch (AmazonClientException ignored) {
            return false;
        }
    }

    @NotNull
    private List<Parameter> convertSystemProperties(@NotNull Map<String, String> configParameters) {
        List<Parameter> parameters = new ArrayList<>();
        for (String s : configParameters.keySet()) {
            if (s.startsWith("cloudformation.")) {
                String key = s.split("cloudformation\\.")[1];
                Parameter parameter = new Parameter();
                parameter.setParameterKey(key);
                parameter.setParameterValue(configParameters.get(s));
                parameters.add(parameter);
            }
        }
        return parameters;
    }

    private void waitForCompletion(AmazonCloudFormationClient stackbuilder, String stackName)
            throws InterruptedException {
        DescribeStacksRequest wait = new DescribeStacksRequest();
        wait.setStackName(stackName);
        Boolean completed = false;
        String stackStatus = "";
        String stackReason = "";

        while (!completed) {
            List<Stack> stacks = stackbuilder.describeStacks(wait).getStacks();
            if (stacks != null) {
                if (!stacks.isEmpty()) {
                    for (Stack stack : stacks) {
                        stackStatus = stack.getStackStatus();
                        if (statusIsCompleted(stackStatus)) {
                            completed = true;

                            if (stackStatus.equals(StackStatus.CREATE_COMPLETE.toString())) {
                                stackReason = "SUCCESS";
                            } else {
                                stackReason = "FAILURE";
                            }
                        }
                    }
                } else {
                    completed = true;
                    stackStatus = "NO_SUCH_STACK";
                    stackReason = "FAILURE";
                }
            } else {
                completed = true;
                stackStatus = "NO_SUCH_STACK";
                stackReason = "FAILURE";
            }

            myListener.waitForStack(stackStatus);
            Seconds seconds = Seconds.ONE.multipliedBy(5);
            Thread.sleep(seconds.toStandardDuration().getMillis());
        }

        myListener.waitForStack(stackStatus);

        if (stackReason.contains("Failure")) {
            myListener.createStackFailed(stackName, stackStatus, stackReason);
        } else {
            myListener.createStackFinished(stackName, stackStatus);
        }
    }

    private boolean statusIsCompleted(String status) {
        TreeSet<String> acceptableValues = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        acceptableValues.add(StackStatus.CREATE_COMPLETE.toString());
        acceptableValues.add(StackStatus.CREATE_FAILED.toString());
        acceptableValues.add(StackStatus.ROLLBACK_FAILED.toString());
        acceptableValues.add(StackStatus.ROLLBACK_COMPLETE.toString());
        acceptableValues.add(StackStatus.UPDATE_COMPLETE.toString());
        acceptableValues.add(StackStatus.UPDATE_ROLLBACK_COMPLETE.toString());
        acceptableValues.add(StackStatus.UPDATE_ROLLBACK_FAILED.toString());
        acceptableValues.add(StackStatus.DELETE_FAILED.toString());
        return acceptableValues.contains(status);
    }

    private void waitForDelete(AmazonCloudFormationClient stackbuilder, String stackName) throws InterruptedException {
        DescribeStacksRequest wait = new DescribeStacksRequest();
        wait.setStackName(stackName);
        String stackStatus;
        String action = "DELETE";
        Boolean delete = false;
        List<String> events;

        while (!delete) {
            List<Stack> stacks = stackbuilder.describeStacks(wait).getStacks();
            if (stacks.isEmpty()) {
                delete = true;
                stackStatus = "NO_SUCH_STACK";
            } else {
                myListener.debugLog("From the wait for delete");
                events = describeStackEvents(stackbuilder, stackName, action);
                for (String event : events) {
                    myListener.waitForStack(event);
                }
                Thread.sleep(10000);
                events.clear();
            }
        }
        stackStatus = "done";
        myListener.waitForStack(stackStatus);
        myListener.createStackFinished(stackName, stackStatus);
    }

    private List<String> describeStackEvents(AmazonCloudFormationClient stackbuilder, String stackName, String ACTION) {
        List<String> output = new ArrayList<String>();
        DescribeStackEventsRequest request = new DescribeStackEventsRequest();
        request.setStackName(stackName);
        DescribeStackEventsResult results = stackbuilder.describeStackEvents(request);
        for (StackEvent event : results.getStackEvents()) {
            if (event.getEventId().contains(ACTION)) {

                output.add(event.getEventId());
                // myListener.debugLog(event.toString());
            }
        }
        return output;
    }

    private String getTemplateUrl(Region region, String s3Bucket, String s3Object) {
        String templateUrl;
        templateUrl = "https://" + region.getServiceEndpoint("s3") + "/" + s3Bucket + "/" + s3Object;
        return templateUrl;
    }

    private void processFailure(@NotNull Throwable t) {
        myListener.exception(new AWSException(t));
    }

    @NotNull
    private String getHumanReadableStatus(@NotNull String status) {
        if (StackStatus.CREATE_IN_PROGRESS.toString().equals(status))
            return "launching";
        if (StackStatus.UPDATE_IN_PROGRESS.toString().equals(status))
            return "updating";
        if (StackStatus.CREATE_COMPLETE.toString().equals(status))
            return "ready";
        if (StackStatus.DELETE_COMPLETE.toString().equals(status))
            return "terminated";
        if (StackStatus.DELETE_IN_PROGRESS.toString().equals(status))
            return "terminating";
        return CloudFormationConstants.STATUS_IS_UNKNOWN;
    }

    @Contract("null -> null")
    @Nullable
    private String removeTrailingDot(@Nullable String msg) {
        return (msg != null && msg.endsWith(".")) ? msg.substring(0, msg.length() - 1) : msg;
    }

    static class Listener {

        void createStackStarted(@NotNull String stackName,
                                @NotNull String region,
                                @NotNull String s3BucketName,
                                @NotNull String s3ObjectKey,
                                @NotNull String cfnAction) {
        }

        void debugLog(String status) {
        }

        void createStackFailed(@NotNull String stackName, @NotNull String stackStatus, @NotNull String stackReason) {
        }

        void createStackFinished(@NotNull String stackName, @NotNull String stackStatus) {
        }

        void waitForStack(@NotNull String status) {
        }

        void deleteStarted(@NotNull String stackName, @NotNull String region) {
        }

        void deleteSucceeded(@NotNull String stackName) {
        }

        void validateStarted(@NotNull String stackName) {
        }

        void validateFinished(@NotNull String parameters) {
        }

        void updateInProgress(@NotNull String stackName) {
        }

        void exception(@NotNull AWSException exception) {
        }

        void deploymentFailed(@NotNull String environmentId,
                              @NotNull String applicationName,
                              @NotNull String versionLabel,
                              @NotNull Boolean hasTimeout,
                              @Nullable ErrorInfo errorInfo) {
        }

        static class ErrorInfo {
            @Nullable
            String severity;
            @Nullable
            String message;
        }
    }
}
