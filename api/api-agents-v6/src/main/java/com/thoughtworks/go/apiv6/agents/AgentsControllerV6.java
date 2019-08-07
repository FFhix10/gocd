/*
 * Copyright 2019 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.thoughtworks.go.apiv6.agents;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.thoughtworks.go.api.ApiController;
import com.thoughtworks.go.api.ApiVersion;
import com.thoughtworks.go.api.CrudController;
import com.thoughtworks.go.api.base.OutputWriter;
import com.thoughtworks.go.api.representers.JsonReader;
import com.thoughtworks.go.api.spring.ApiAuthenticationHelper;
import com.thoughtworks.go.api.util.GsonTransformer;
import com.thoughtworks.go.api.util.MessageJson;
import com.thoughtworks.go.apiv6.agents.model.AgentBulkUpdateRequest;
import com.thoughtworks.go.apiv6.agents.model.AgentUpdateRequest;
import com.thoughtworks.go.apiv6.agents.representers.AgentBulkUpdateRequestRepresenter;
import com.thoughtworks.go.apiv6.agents.representers.AgentsRepresenter;
import com.thoughtworks.go.config.EnvironmentConfig;
import com.thoughtworks.go.config.EnvironmentsConfig;
import com.thoughtworks.go.config.exceptions.EntityType;
import com.thoughtworks.go.config.exceptions.HttpException;
import com.thoughtworks.go.domain.AgentInstance;
import com.thoughtworks.go.domain.NullAgentInstance;
import com.thoughtworks.go.server.service.AgentService;
import com.thoughtworks.go.server.service.EnvironmentConfigService;
import com.thoughtworks.go.server.service.SecurityService;
import com.thoughtworks.go.server.service.result.HttpLocalizedOperationResult;
import com.thoughtworks.go.server.service.result.HttpOperationResult;
import com.thoughtworks.go.spark.Routes;
import com.thoughtworks.go.spark.spring.SparkSpringController;
import com.thoughtworks.go.util.TriState;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import spark.Request;
import spark.Response;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

import static com.thoughtworks.go.apiv6.agents.representers.AgentRepresenter.toJSON;
import static com.thoughtworks.go.apiv6.agents.representers.AgentUpdateRequestRepresenter.fromJSON;
import static com.thoughtworks.go.util.CommaSeparatedString.commaSeparatedStrToList;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toCollection;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static spark.Spark.*;

@SuppressWarnings("ALL")
@Component
public class AgentsControllerV6 extends ApiController implements SparkSpringController, CrudController<AgentInstance> {
    private final AgentService agentService;
    private final ApiAuthenticationHelper apiAuthenticationHelper;
    private final SecurityService securityService;
    private final EnvironmentConfigService environmentConfigService;

    @Autowired
    public AgentsControllerV6(AgentService agentService, ApiAuthenticationHelper apiAuthenticationHelper,
                              SecurityService securityService, EnvironmentConfigService environmentConfigService) {
        super(ApiVersion.v6);
        this.agentService = agentService;
        this.apiAuthenticationHelper = apiAuthenticationHelper;
        this.securityService = securityService;
        this.environmentConfigService = environmentConfigService;
    }

    @Override
    public String controllerBasePath() {
        return Routes.AgentsAPI.BASE;
    }

    @Override
    public void setupRoutes() {
        path(controllerBasePath(), () -> {
            before("", mimeType, this::setContentType);
            before("/*", mimeType, this::setContentType);
            before("", mimeType, this::checkSecurityOr403);
            before("/*", mimeType, this::checkSecurityOr403);

            get("", mimeType, this::index);
            get(Routes.AgentsAPI.UUID, mimeType, this::show);
            patch(Routes.AgentsAPI.UUID, mimeType, this::update);
            patch("", mimeType, this::bulkUpdate);
            delete(Routes.AgentsAPI.UUID, mimeType, this::deleteAgent);
            delete("", mimeType, this::bulkDeleteAgents);

            exception(HttpException.class, this::httpException);
        });
    }

    public String index(Request request, Response response) throws IOException {
        Map<AgentInstance, Collection<EnvironmentConfig>> agentToEnvConfigsMap = new HashMap<>();

        agentService.getAgentInstances().values().forEach(agentInstance -> {
            Set<EnvironmentConfig> envConfigSet = environmentConfigService.getAgentEnvironments(agentInstance.getUuid());
            agentToEnvConfigsMap.put(agentInstance, envConfigSet);
        });

        return writerForTopLevelObject(request, response,
                outputWriter -> AgentsRepresenter.toJSON(outputWriter, agentToEnvConfigsMap, securityService, currentUsername()));
    }

    public String show(Request request, Response response) throws IOException {
        String uuid = request.params("uuid");
        final AgentInstance agentInstance = fetchEntityFromConfig(uuid);

        return writerForTopLevelObject(request, response,
                outputWriter -> toJSON(outputWriter, agentInstance, environmentConfigService.getAgentEnvironments(uuid),
                        securityService, currentUsername()));
    }

    public String update(Request request, Response response) {
        String uuid = request.params("uuid");
        AgentUpdateRequest req = fromJSON(request.body());

        String hostname = req.getHostname();
        String resources = req.getResources();
        TriState configState = req.getAgentConfigState();

        EnvironmentsConfig envsConfig = createEnvironmentsConfigFrom(req.getEnvironments());
        HttpOperationResult result = new HttpOperationResult();
        AgentInstance updatedAgentInstance = agentService.updateAgentAttributes(uuid, hostname, resources, envsConfig, configState, result);

        return handleCreateOrUpdateResponse(request, response, updatedAgentInstance, result);
    }

    public String bulkUpdate(Request request, Response response) throws IOException {
        final AgentBulkUpdateRequest req = AgentBulkUpdateRequestRepresenter.fromJSON(request.body());

        final HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();

        EnvironmentsConfig envsConfig = createEnvironmentsConfigFrom(req.getOperations().getEnvironments().toAdd());
        agentService.bulkUpdateAgentAttributes(
                req.getUuids(),
                req.getOperations().getResources().toAdd(),
                req.getOperations().getResources().toRemove(),
                envsConfig,
                req.getOperations().getEnvironments().toRemove(),
                req.getAgentConfigState(),
                result);

        return renderHTTPOperationResult(result, request, response);
    }

    private EnvironmentsConfig createEnvironmentsConfigFrom(String commaSeparatedEnvs) {
        if (commaSeparatedEnvs == null) {
            return null;
        }

        if (isBlank(commaSeparatedEnvs)) {
            return new EnvironmentsConfig();
        }

        return createEnvironmentsConfigFrom(commaSeparatedStrToList(commaSeparatedEnvs));
    }

    private EnvironmentsConfig createEnvironmentsConfigFrom(List<String> envList) {
        if (envList != null) {
            return envList.stream()
                    .filter(StringUtils::isNotBlank)
                    .map(String::trim)
                    .map(environmentConfigService::findOrDefault)
                    .collect(toCollection(EnvironmentsConfig::new));
        }
        return new EnvironmentsConfig();
    }

    public String deleteAgent(Request request, Response response) throws IOException {
        final HttpOperationResult result = new HttpOperationResult();
        agentService.deleteAgents(singletonList(request.params("uuid")), result);
        return renderHTTPOperationResult(result, request, response);
    }

    public String bulkDeleteAgents(Request request, Response response) throws IOException {
        final JsonReader reader = GsonTransformer.getInstance().jsonReaderFrom(request.body());
        final List<String> uuids = toList(reader.optJsonArray("uuids").orElse(new JsonArray()));

        final HttpOperationResult result = new HttpOperationResult();
        agentService.deleteAgents(uuids, result);

        return renderHTTPOperationResult(result, request, response);
    }

    @Override
    public String etagFor(AgentInstance entityFromServer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public EntityType getEntityType() {
        return EntityType.Agent;
    }

    @Override
    public AgentInstance doFetchEntityFromConfig(String uuid) {
        final AgentInstance agentInstance = agentService.findAgent(uuid);

        return agentInstance instanceof NullAgentInstance ? null : agentInstance;
    }

    @Override
    public AgentInstance buildEntityFromRequestBody(Request req) {
        return null;
    }

    @Override
    public Consumer<OutputWriter> jsonWriter(AgentInstance agentInstance) {
        return outputWriter -> toJSON(outputWriter, agentInstance,
                environmentConfigService.getAgentEnvironments(agentInstance.getUuid()),
                securityService, currentUsername());
    }

    private void checkSecurityOr403(Request request, Response response) {
        if (asList("GET", "HEAD").contains(request.requestMethod().toUpperCase())) {
            apiAuthenticationHelper.checkUserAnd403(request, response);
            return;
        }
        apiAuthenticationHelper.checkAdminUserAnd403(request, response);
    }

    private List<String> toList(JsonArray jsonArr) {
        final List<String> list = new ArrayList<>();
        for (JsonElement jsonElement : jsonArr) {
            list.add(jsonElement.getAsString());
        }
        return list;
    }

    private String handleCreateOrUpdateResponse(Request req, Response res, AgentInstance agentInstance, HttpOperationResult result) {
        if (result.isSuccess()) {
            return jsonize(req, agentInstance);
        } else {
            res.status(result.httpCode());
            String errorMessage = result.message();
            return null == agentInstance ? MessageJson.create(errorMessage) : MessageJson.create(errorMessage, jsonWriter(agentInstance));
        }
    }
}
