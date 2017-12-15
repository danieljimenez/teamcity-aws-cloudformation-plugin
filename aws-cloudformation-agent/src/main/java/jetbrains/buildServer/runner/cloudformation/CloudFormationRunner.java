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

import jetbrains.buildServer.RunBuildException;
import jetbrains.buildServer.agent.AgentBuildRunner;
import jetbrains.buildServer.agent.AgentBuildRunnerInfo;
import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.agent.BuildAgentConfiguration;
import jetbrains.buildServer.agent.BuildFinishedStatus;
import jetbrains.buildServer.agent.BuildProcess;
import jetbrains.buildServer.agent.BuildRunnerContext;
import jetbrains.buildServer.messages.ErrorData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

import static jetbrains.buildServer.runner.cloudformation.CloudFormationConstants.*;
import static jetbrains.buildServer.util.StringUtil.nullIfEmpty;
import static jetbrains.buildServer.util.amazon.AWSCommonParams.*;

public class CloudFormationRunner implements AgentBuildRunner {
  @NotNull
  @Override
  public BuildProcess createBuildProcess(@NotNull final AgentRunningBuild runningBuild, @NotNull final BuildRunnerContext context) throws RunBuildException {
    return new SyncBuildProcessAdapter() {
      @NotNull
      @Override
      protected BuildFinishedStatus runImpl() throws RunBuildException {

        final Map<String, String> runnerParameters = validateParams();
        final Map<String, String> configParameters = context.getConfigParameters();

        final Mutable m = new Mutable(configParameters);
        m.problemOccurred = false;
        m.s3ObjectVersion = nullIfEmpty(configParameters.get(S3_OBJECT_VERSION_CONFIG_PARAM));

        final AWSClient awsClient = createAWSClient(runnerParameters, runningBuild).withListener(
            new LoggingDeploymentListener(runnerParameters, runningBuild.getBuildLogger(), runningBuild.getCheckoutDirectory().getAbsolutePath()));

        final String s3BucketName = runnerParameters.get(S3_BUCKET_NAME_PARAM);
        String s3ObjectKey = runnerParameters.get(S3_OBJECT_KEY_PARAM);
        final String region = runnerParameters.get(REGION_NAME_PARAM);
        final String cfnAction = runnerParameters.get(CLOUDFORMATION_STACK_ACTION_PARAM);
        final String stackName = runnerParameters.get(STACK_NAME_PARAM);
        final String onFailure = runnerParameters.get(ONFAILURE_PARAM);

        if (!m.problemOccurred && !isInterrupted()) {
          awsClient.initiateCFN(stackName, region, s3BucketName, s3ObjectKey, cfnAction, onFailure, context.getBuildParameters().getSystemProperties());
        }
        
        return m.problemOccurred ? BuildFinishedStatus.FINISHED_WITH_PROBLEMS : BuildFinishedStatus.FINISHED_SUCCESS;
      }

      @NotNull
      private Map<String, String> validateParams() throws RunBuildException {
        final Map<String, String> runnerParameters = context.getRunnerParameters();
        final Map<String, String> invalids = ParametersValidator.validateRuntime(runnerParameters, context.getConfigParameters(), runningBuild.getCheckoutDirectory());
        if (invalids.isEmpty()) return runnerParameters;
        throw new CloudFormationRunnerException(CloudFormationUtil.printStrings(invalids.values()), null);
      }
    };
  }

  @NotNull
  @Override
  public AgentBuildRunnerInfo getRunnerInfo() {
    return new AgentBuildRunnerInfo() {
      @NotNull
      @Override
      public String getType() {
        return RUNNER_TYPE;
      }

      @Override
      public boolean canRun(@NotNull BuildAgentConfiguration agentConfiguration) {
        return true;
      }
    };
  }

  @NotNull
  private AWSClient createAWSClient(final Map<String, String> runnerParameters, @NotNull final AgentRunningBuild runningBuild) {
    final Map<String, String> params = new HashMap<String, String>(runnerParameters);
    params.put(TEMP_CREDENTIALS_SESSION_NAME_PARAM, runningBuild.getBuildTypeExternalId() + runningBuild.getBuildId());
//    if (CloudFormationUtil.isDeploymentWaitEnabled(runnerParameters)) {
//      params.put(TEMP_CREDENTIALS_DURATION_SEC_PARAM, String.valueOf(2 * Integer.parseInt(runnerParameters.get(WAIT_TIMEOUT_SEC_PARAM))));
//    }

    return new AWSClient(createAWSClients(params, true)).withDescription("TeamCity build \"" + runningBuild.getBuildTypeName() + "\" #" + runningBuild.getBuildNumber());
  }

  static class CloudFormationRunnerException extends RunBuildException {
    public CloudFormationRunnerException(@NotNull String message, @Nullable Throwable cause) {
      super(message, cause, ErrorData.BUILD_RUNNER_ERROR_TYPE);
      this.setLogStacktrace(false);
    }
  }

  private class Mutable {
    public Mutable(@NotNull Map<String, String> configParameters) {
      problemOccurred = false;
      s3ObjectVersion = nullIfEmpty(configParameters.get(S3_OBJECT_VERSION_CONFIG_PARAM));
    }

    boolean problemOccurred;
    String s3ObjectVersion;
    String s3ObjectETag;
  }
}
