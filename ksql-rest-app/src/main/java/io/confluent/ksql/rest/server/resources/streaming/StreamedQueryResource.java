/**
 * Copyright 2017 Confluent Inc.
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
 **/

package io.confluent.ksql.rest.server.resources.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.ksql.KsqlEngine;
import io.confluent.ksql.parser.tree.PrintTopic;
import io.confluent.ksql.parser.tree.Query;
import io.confluent.ksql.parser.tree.Statement;
import io.confluent.ksql.rest.entity.KsqlRequest;
import io.confluent.ksql.rest.entity.Versions;
import io.confluent.ksql.rest.server.StatementParser;
import io.confluent.ksql.rest.server.resources.Errors;
import io.confluent.ksql.rest.server.resources.KsqlRestException;
import io.confluent.ksql.rest.util.JsonMapper;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.version.metrics.ActivenessRegistrar;
import java.time.Duration;
import java.util.Objects;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/query")
@Produces({Versions.KSQL_V1_JSON, MediaType.APPLICATION_JSON})
@Consumes({Versions.KSQL_V1_JSON, MediaType.APPLICATION_JSON})
public class StreamedQueryResource {

  private static final Logger log = LoggerFactory.getLogger(StreamedQueryResource.class);

  private final KsqlConfig ksqlConfig;
  private final KsqlEngine ksqlEngine;
  private final StatementParser statementParser;
  private final Duration disconnectCheckInterval;
  private final ObjectMapper objectMapper;
  private final ActivenessRegistrar activenessRegistrar;

  public StreamedQueryResource(
      final KsqlConfig ksqlConfig,
      final KsqlEngine ksqlEngine,
      final StatementParser statementParser,
      final Duration disconnectCheckInterval,
      final ActivenessRegistrar activenessRegistrar
  ) {
    this.ksqlConfig = ksqlConfig;
    this.ksqlEngine = ksqlEngine;
    this.statementParser = statementParser;
    this.disconnectCheckInterval =
        Objects.requireNonNull(disconnectCheckInterval, "disconnectCheckInterval");
    this.objectMapper = JsonMapper.INSTANCE.mapper;
    this.activenessRegistrar =
        Objects.requireNonNull(activenessRegistrar, "activenessRegistrar");
  }

  @POST
  public Response streamQuery(final KsqlRequest request) throws Exception {
    final String ksql = request.getKsql();
    final Statement statement;
    if (ksql.isEmpty()) {
      return Errors.badRequest("\"ksql\" field must be populated");
    }
    activenessRegistrar.updateLastRequestTime();
    try {
      statement = statementParser.parseSingleStatement(ksql);
    } catch (IllegalArgumentException | KsqlException e) {
      return Errors.badRequest(e);
    }

    if (statement instanceof Query) {
      final QueryStreamWriter queryStreamWriter;
      try {
        queryStreamWriter = new QueryStreamWriter(
            ksqlConfig,
            ksqlEngine,
            disconnectCheckInterval.toMillis(),
            ksql,
            request.getStreamsProperties(),
            objectMapper);
      } catch (final KsqlException e) {
        return Errors.badRequest(e);
      }
      log.info("Streaming query '{}'", ksql);
      return Response.ok().entity(queryStreamWriter).build();

    } else if (statement instanceof PrintTopic) {
      final TopicStreamWriter topicStreamWriter = getTopicStreamWriter(
          (PrintTopic) statement
      );
      return Response.ok().entity(topicStreamWriter).build();
    }
    return Errors.badRequest(String .format(
        "Statement type `%s' not supported for this resource",
        statement.getClass().getName()));
  }

  private TopicStreamWriter getTopicStreamWriter(final PrintTopic printTopic) {
    final String topicName = printTopic.getTopic().toString();

    if (!ksqlEngine.getTopicClient().isTopicExists(topicName)) {
      throw new KsqlRestException(
          Errors.badRequest(String.format(
              "Could not find topic '%s', KSQL uses uppercase.%n"
              + "To print a case-sensitive topic apply quotations, for example: print \'topic\';",
              topicName)));
    }
    final TopicStreamWriter topicStreamWriter = new TopicStreamWriter(
        ksqlEngine.getSchemaRegistryClient(),
        ksqlConfig.getKsqlStreamConfigProps(),
        topicName,
        printTopic.getIntervalValue(),
        disconnectCheckInterval,
        printTopic.getFromBeginning()
    );
    log.info("Printing topic '{}'", topicName);
    return topicStreamWriter;
  }
}
