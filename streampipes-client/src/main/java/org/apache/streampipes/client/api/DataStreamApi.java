/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.apache.streampipes.client.api;

import org.apache.streampipes.client.live.EventProcessor;
import org.apache.streampipes.client.live.SubscriptionManager;
import org.apache.streampipes.client.model.StreamPipesClientConfig;
import org.apache.streampipes.client.util.StreamPipesApiPath;
import org.apache.streampipes.messaging.kafka.SpKafkaConsumer;
import org.apache.streampipes.model.SpDataStream;
import org.apache.streampipes.model.message.Message;

import java.net.URLEncoder;
import java.util.List;

public class DataStreamApi extends AbstractClientApi<SpDataStream> implements CRUDApi<String, SpDataStream> {

  public DataStreamApi(StreamPipesClientConfig clientConfig) {
    super(clientConfig, SpDataStream.class);
  }

  @Override
  public SpDataStream get(String s) {
    return null;
  }

  @Override
  public List<SpDataStream> all() {
    return getAll(getBaseResourcePath());
  }

  @Override
  public void create(SpDataStream element) {

  }

  @Override
  public void delete(String s) {
    delete(getBaseResourcePath().addToPath(URLEncoder.encode(s)), Message.class);
  }

  @Override
  public void update(SpDataStream element) {

  }

  public SpKafkaConsumer subscribe(SpDataStream stream, EventProcessor callback) {
    return new SubscriptionManager(clientConfig, stream.getEventGrounding(), callback).subscribe();
  }

  @Override
  protected StreamPipesApiPath getBaseResourcePath() {
    return StreamPipesApiPath.fromUserApiPath(clientConfig.getCredentials())
            .addToPath("streams")
            .addToPath("own");
  }
}
