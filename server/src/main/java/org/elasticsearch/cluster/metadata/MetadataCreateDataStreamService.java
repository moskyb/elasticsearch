/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.cluster.metadata;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.create.CreateIndexClusterStateUpdateRequest;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.action.support.ActiveShardsObserver;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ack.ClusterStateUpdateRequest;
import org.elasticsearch.cluster.ack.ClusterStateUpdateResponse;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ObjectPath;
import org.elasticsearch.index.mapper.DateFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class MetadataCreateDataStreamService {

    private static final Logger logger = LogManager.getLogger(MetadataCreateDataStreamService.class);
    public static final Set<String> ALLOWED_TIMESTAMPFIELD_TYPES =
        new LinkedHashSet<>(List.of(DateFieldMapper.CONTENT_TYPE, DateFieldMapper.DATE_NANOS_CONTENT_TYPE));

    private final ClusterService clusterService;
    private final ActiveShardsObserver activeShardsObserver;
    private final MetadataCreateIndexService metadataCreateIndexService;

    public MetadataCreateDataStreamService(ThreadPool threadPool,
                                           ClusterService clusterService,
                                           MetadataCreateIndexService metadataCreateIndexService) {
        this.clusterService = clusterService;
        this.activeShardsObserver = new ActiveShardsObserver(clusterService, threadPool);
        this.metadataCreateIndexService = metadataCreateIndexService;
    }

    public void createDataStream(CreateDataStreamClusterStateUpdateRequest request,
                                 ActionListener<AcknowledgedResponse> finalListener) {
        AtomicReference<String> firstBackingIndexRef = new AtomicReference<>();
        ActionListener<ClusterStateUpdateResponse> listener = ActionListener.wrap(
            response -> {
                if (response.isAcknowledged()) {
                    String firstBackingIndexName = firstBackingIndexRef.get();
                    assert firstBackingIndexName != null;
                    activeShardsObserver.waitForActiveShards(
                        new String[]{firstBackingIndexName},
                        ActiveShardCount.DEFAULT,
                        request.masterNodeTimeout(),
                        shardsAcked -> {
                            finalListener.onResponse(new AcknowledgedResponse(true));
                        },
                        finalListener::onFailure);
                } else {
                    finalListener.onResponse(new AcknowledgedResponse(false));
                }
            },
            finalListener::onFailure
        );
        clusterService.submitStateUpdateTask("create-data-stream [" + request.name + "]",
            new AckedClusterStateUpdateTask<>(Priority.HIGH, request, listener) {

                @Override
                public ClusterState execute(ClusterState currentState) throws Exception {
                    ClusterState clusterState = createDataStream(metadataCreateIndexService, currentState, request);
                    firstBackingIndexRef.set(clusterState.metadata().dataStreams().get(request.name).getIndices().get(0).getName());
                    return clusterState;
                }

                @Override
                protected ClusterStateUpdateResponse newResponse(boolean acknowledged) {
                    return new ClusterStateUpdateResponse(acknowledged);
                }
            });
    }

    public ClusterState createDataStream(CreateDataStreamClusterStateUpdateRequest request, ClusterState current) throws Exception {
        return createDataStream(metadataCreateIndexService, current, request);
    }

    public static final class CreateDataStreamClusterStateUpdateRequest extends ClusterStateUpdateRequest {

        private final String name;

        public CreateDataStreamClusterStateUpdateRequest(String name,
                                                         TimeValue masterNodeTimeout,
                                                         TimeValue timeout) {
            this.name = name;
            masterNodeTimeout(masterNodeTimeout);
            ackTimeout(timeout);
        }
    }

    static ClusterState createDataStream(MetadataCreateIndexService metadataCreateIndexService,
                                         ClusterState currentState,
                                         CreateDataStreamClusterStateUpdateRequest request)
        throws Exception {
        if (currentState.nodes().getMinNodeVersion().before(Version.V_7_9_0)) {
            throw new IllegalStateException("data streams require minimum node version of " + Version.V_7_9_0);
        }
        if (currentState.metadata().dataStreams().containsKey(request.name)) {
            throw new IllegalArgumentException("data_stream [" + request.name + "] already exists");
        }

        MetadataCreateIndexService.validateIndexOrAliasName(request.name,
            (s1, s2) -> new IllegalArgumentException("data_stream [" + s1 + "] " + s2));

        if (request.name.toLowerCase(Locale.ROOT).equals(request.name) == false) {
            throw new IllegalArgumentException("data_stream [" + request.name + "] must be lowercase");
        }
        if (request.name.startsWith(".")) {
            throw new IllegalArgumentException("data_stream [" + request.name + "] must not start with '.'");
        }

        ComposableIndexTemplate template = lookupTemplateForDataStream(request.name, currentState.metadata());

        String firstBackingIndexName = DataStream.getDefaultBackingIndexName(request.name, 1);
        CreateIndexClusterStateUpdateRequest createIndexRequest =
            new CreateIndexClusterStateUpdateRequest("initialize_data_stream", firstBackingIndexName, firstBackingIndexName)
                .dataStreamName(request.name)
                .settings(Settings.builder().put("index.hidden", true).build());
        currentState = metadataCreateIndexService.applyCreateIndexRequest(currentState, createIndexRequest, false);
        IndexMetadata firstBackingIndex = currentState.metadata().index(firstBackingIndexName);
        assert firstBackingIndex != null;
        assert firstBackingIndex.mapping() != null : "no mapping found for backing index [" + firstBackingIndexName + "]";

        String fieldName = template.getDataStreamTemplate().getTimestampField();
        Map<String, Object> mapping = firstBackingIndex.mapping().getSourceAsMap();
        Map<String, Object> timeStampFieldMapping = ObjectPath.eval(convertFieldPathToMappingPath(fieldName), mapping);

        DataStream.TimestampField timestampField = new DataStream.TimestampField(
            fieldName,
            timeStampFieldMapping
        );
        DataStream newDataStream = new DataStream(request.name, timestampField, List.of(firstBackingIndex.getIndex()));
        Metadata.Builder builder = Metadata.builder(currentState.metadata()).put(newDataStream);
        logger.info("adding data stream [{}]", request.name);
        return ClusterState.builder(currentState).metadata(builder).build();
    }

    public static ComposableIndexTemplate lookupTemplateForDataStream(String dataStreamName, Metadata metadata) {
        final String v2Template = MetadataIndexTemplateService.findV2Template(metadata, dataStreamName, false);
        if (v2Template == null) {
            throw new IllegalArgumentException("no matching index template found for data stream [" + dataStreamName + "]");
        }
        ComposableIndexTemplate composableIndexTemplate = metadata.templatesV2().get(v2Template);
        if (composableIndexTemplate.getDataStreamTemplate() == null) {
            throw new IllegalArgumentException("matching index template [" + v2Template + "] for data stream [" + dataStreamName  +
                "] has no data stream template");
        }
        return composableIndexTemplate;
    }

    public static void validateTimestampFieldMapping(String timestampFieldName, MapperService mapperService) {
        MappedFieldType timestampFieldMapper = mapperService.fieldType(timestampFieldName);
        if (timestampFieldMapper == null) {
            throw new IllegalArgumentException("expected timestamp field [" + timestampFieldName + "], but found no timestamp field");
        }
        String type = timestampFieldMapper.typeName();
        if (ALLOWED_TIMESTAMPFIELD_TYPES.contains(type) == false) {
            throw new IllegalArgumentException("expected timestamp field [" + timestampFieldName + "] to be of types " +
                ALLOWED_TIMESTAMPFIELD_TYPES + ", but instead found type [" + type  + "]");
        }
    }

    public static String convertFieldPathToMappingPath(String fieldPath) {
        // The mapping won't allow such fields, so this is a sanity check:
        assert Arrays.stream(fieldPath.split("\\.")).filter(String::isEmpty).count() == 0L ||
            fieldPath.startsWith(".") ||
            fieldPath.endsWith(".") : "illegal field path [" + fieldPath + "]";

        String mappingPath;
        if (fieldPath.indexOf('.') == -1) {
            mappingPath = "properties." + fieldPath;
        } else {
            mappingPath = "properties." + fieldPath.replace(".", ".properties.");
        }

        return mappingPath;
    }

}
